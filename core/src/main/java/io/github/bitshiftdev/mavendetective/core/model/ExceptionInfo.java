package io.github.bitshiftdev.mavendetective.core.model;

/** Ein Glied einer Exception-Kette: Klasse und Nachricht. */
public final class ExceptionInfo {

    private final String className;
    private final String message;

    public ExceptionInfo(String className, String message) {
        this.className = className;
        this.message = message;
    }

    public String getClassName() { return className; }

    public String getMessage() { return message; }

    @Override
    public String toString() {
        return message == null ? className : className + ": " + message;
    }
}
