package io.github.bitshiftdev.mavendetective.core.model;

/** Ein Problem beim Einlesen einer POM, mit Datei und Position, sofern Maven sie meldet. */
public final class PomProblem {

    private final String source;
    private final int line;
    private final int column;
    private final String severity;
    private final String message;

    public PomProblem(String source, int line, int column, String severity, String message) {
        this.source = source;
        this.line = line;
        this.column = column;
        this.severity = severity;
        this.message = message;
    }

    /** Pfad der POM oder Koordinaten des Modells; kann {@code null} sein. */
    public String getSource() { return source; }

    /** Zeilennummer ab 1, oder -1 wenn unbekannt. */
    public int getLine() { return line; }

    public int getColumn() { return column; }

    public String getSeverity() { return severity; }

    public String getMessage() { return message; }
}
