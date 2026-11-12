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

    /** Alle Fehlertexte zeilenweise und ohne Duplikate – das ist der Suchraum der Regeln. */
    public List<String> searchableLines() {
        Set<String> seen = new LinkedHashSet<String>();
        for (ErrorRecord e : errors) {
            for (String line : e.lines()) {
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
