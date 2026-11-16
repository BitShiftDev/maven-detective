package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Was eine Prüfung sieht: den Fehler, die Umgebung und die bisher gesammelten Variablen. */
public final class CheckContext {

    private final BuildFailure failure;
    private final Environment environment;
    private final Map<String, String> vars;
    private final List<String> lines;
    private final ProjectPoms projectPoms;
    private final Cancellation cancellation;

    public CheckContext(BuildFailure failure, Environment environment, Map<String, String> vars,
                        List<String> lines, ProjectPoms projectPoms) {
        this(failure, environment, vars, lines, projectPoms, Cancellation.NONE);
    }

    public CheckContext(BuildFailure failure, Environment environment, Map<String, String> vars,
                        List<String> lines, ProjectPoms projectPoms, Cancellation cancellation) {
        this.cancellation = cancellation == null ? Cancellation.NONE : cancellation;
        this.failure = failure;
        this.environment = environment;
        this.vars = Collections.unmodifiableMap(vars);
        this.lines = lines;
        this.projectPoms = projectPoms;
    }

    public BuildFailure failure() { return failure; }

    public Environment env() { return environment; }

    public String var(String name) { return vars.get(name); }

    public Map<String, String> vars() { return vars; }

    /** Durchsuchbare Fehlerzeilen. */
    public List<String> lines() { return lines; }

    public ProjectPoms projectPoms() { return projectPoms; }

    /** Abbruch der IDE für lange Suchen (Klassen-Herkunft); sonst {@link Cancellation#NONE}. */
    public Cancellation cancellation() { return cancellation; }

    /** Erste Zeile, in der die Regex trifft, als Matcher – oder {@code null}. */
    public Matcher find(Pattern p) {
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return m;
            }
        }
        return null;
    }

    public boolean anyLineContains(String text) {
        for (String line : lines) {
            if (line.contains(text)) {
                return true;
            }
        }
        return false;
    }

    public List<String> linesContaining(String text) {
        List<String> out = new ArrayList<String>();
        for (String line : lines) {
            if (line.contains(text)) {
                out.add(line);
            }
        }
        return out;
    }
}
