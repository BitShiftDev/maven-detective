package io.github.bitshiftdev.mavendetective.core.diagnosis;

/** Ein Beleg auf der Diagnose-Karte: Logzeile, geprüfte Datei, Einstellung oder Hinweis. */
public final class Evidence {

    public enum Kind { LOG_LINE, FILE, SETTING, ENVIRONMENT, HINT }

    private final Kind kind;
    private final String text;
    private final String file;
    private final int line;

    public Evidence(Kind kind, String text, String file, int line) {
        this.kind = kind;
        this.text = text;
        this.file = file;
        this.line = line;
    }

    public static Evidence log(String text) {
        return new Evidence(Kind.LOG_LINE, text, null, -1);
    }

    public static Evidence file(String text, String file, int line) {
        return new Evidence(Kind.FILE, text, file, line);
    }

    public static Evidence setting(String text, String file, int line) {
        return new Evidence(Kind.SETTING, text, file, line);
    }

    public static Evidence environment(String text) {
        return new Evidence(Kind.ENVIRONMENT, text, null, -1);
    }

    public static Evidence hint(String text) {
        return new Evidence(Kind.HINT, text, null, -1);
    }

    public Kind getKind() { return kind; }

    public String getText() { return text; }

    /** Datei, zu der die IDE springen kann, oder {@code null}. */
    public String getFile() { return file; }

    /** Zeile ab 1, oder -1. */
    public int getLine() { return line; }

    @Override
    public String toString() {
        return file == null ? text : text + " (" + file + (line > 0 ? ":" + line : "") + ")";
    }
}
