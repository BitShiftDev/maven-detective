package io.github.bitshiftdev.mavendetective.core.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Alles, was über einen gescheiterten Maven-Lauf bekannt ist. Entsteht entweder aus den
 * strukturierten Events der Extension oder, als Fallback, aus einem Text-Log.
 */
public final class BuildFailure {

    public enum Source { EVENTS, LOG }

    private final Source source;
    private final SessionInfo session = new SessionInfo();
    private final List<ErrorRecord> errors = new ArrayList<ErrorRecord>();
    private final List<RepositoryRequest> repositoryRequests = new ArrayList<RepositoryRequest>();
    private final List<String> logLines = new ArrayList<String>();

    public BuildFailure(Source source) {
        this.source = source;
    }

    public Source getSource() { return source; }

    public SessionInfo getSession() { return session; }

    public List<ErrorRecord> getErrors() { return errors; }

    public List<RepositoryRequest> getRepositoryRequests() { return repositoryRequests; }

    /** Vollständiges Log (nur bei {@link Source#LOG}), für Prüfungen, die Kontext brauchen. */
    public List<String> getLogLines() { return logLines; }

    public boolean hasErrors() { return !errors.isEmpty(); }

    /**
     * Längste durchsuchte Zeile. Längere (Classpath, Base64, minifizierter Text) werden gekürzt: Regex mit
     * {@code find()} wachsen sonst quadratisch mit der Länge, und die Extension diagnostiziert am Build-Ende.
     */
    public static final int MAX_LINE_LENGTH = 4000;

    /** Alle Fehlertexte zeilenweise und ohne Duplikate – das ist der Suchraum der Regeln. */
    public List<String> searchableLines() {
        return searchableLines(true);
    }

    /** Wie {@link #searchableLines()}; ohne {@code withTestOutput} fehlen Zeilen aus gescheiterten Tests. */
    public List<String> searchableLines(boolean withTestOutput) {
        Set<String> seen = new LinkedHashSet<String>();
        for (ErrorRecord e : errors) {
            if (!withTestOutput && e.isTestOutput()) {
                continue;
            }
            for (String line : e.lines()) {
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH);
                }
                // Exception-Texte von Maven enthalten selbst "[ERROR] "-Präfixe
                String t = line.trim().replaceFirst("^(?:\\[(?:ERROR|FATAL|WARNING)\\]\\s*)+", "").trim();
                if (!t.isEmpty() && !t.equals("@")) {
                    seen.add(t);
                }
            }
        }
        return new ArrayList<String>(seen);
    }
}
