package io.github.bitshiftdev.mavendetective.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein einzelner Fehler eines Maven-Laufs. Aus strukturierten Events stammen Projekt, Mojo,
 * Exception-Kette und POM-Probleme; aus einem Text-Log nur die Logzeile in {@link #getText()}.
 */
public final class ErrorRecord {

    private String project;
    private String mojo;
    private String execution;
    private String text;
    private final List<ExceptionInfo> chain = new ArrayList<ExceptionInfo>();
    private final List<PomProblem> problems = new ArrayList<PomProblem>();

    public static ErrorRecord ofText(String text) {
        ErrorRecord r = new ErrorRecord();
        r.text = text;
        return r;
    }

    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    /** Mojo in der Form {@code groupId:artifactId:version:goal}. */
    public String getMojo() { return mojo; }
    public void setMojo(String mojo) { this.mojo = mojo; }

    public String getExecution() { return execution; }
    public void setExecution(String execution) { this.execution = execution; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public List<ExceptionInfo> getChain() { return chain; }

    public List<PomProblem> getProblems() { return problems; }

    /** Alle Texte dieses Fehlers, zeilenweise, in der Reihenfolge: Text, Kette, POM-Probleme. */
    public List<String> lines() {
        List<String> out = new ArrayList<String>();
        addLines(out, text);
        for (ExceptionInfo e : chain) {
            addLines(out, e.getMessage());
            if (e.getClassName() != null) {
                out.add(e.getClassName());
            }
        }
        for (PomProblem p : problems) {
            String location = "";
            if (p.getLine() > 0) {
                location = " @ " + (p.getSource() == null ? "" : p.getSource() + ", ")
                        + "line " + p.getLine() + ", column " + p.getColumn();
            }
            addLines(out, p.getMessage() + location);
        }
        return out;
    }

    private static void addLines(List<String> out, String text) {
        if (text == null) {
            return;
        }
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                out.add(line);
            }
        }
    }
}
