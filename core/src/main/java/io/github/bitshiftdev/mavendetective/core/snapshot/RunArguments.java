package io.github.bitshiftdev.mavendetective.core.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Profile ({@code -P}) und Properties ({@code -D}) eines Maven-Aufrufs, aus seinen Argumenten gelesen.
 *
 * <p>Die IDE kennt die Profile einer Run-Konfiguration nur zum Teil aus deren Profil-Liste – {@code -Pgreen}
 * kann genauso in den Goals oder den Kommandozeilen-Optionen stehen. Ohne diese Argumente sähen ein grüner
 * {@code verify -Pgreen} und ein roter {@code verify} gleich aus.
 */
public final class RunArguments {

    private final Set<String> activeProfiles = new LinkedHashSet<String>();
    private final Set<String> inactiveProfiles = new LinkedHashSet<String>();
    private final Map<String, String> properties = new TreeMap<String, String>();

    public Set<String> getActiveProfiles() { return activeProfiles; }

    public Set<String> getInactiveProfiles() { return inactiveProfiles; }

    /** {@code -D}-Properties; {@code -Dflag} ohne Wert steht wie bei Maven als {@code true} drin. */
    public Map<String, String> getProperties() { return properties; }

    /**
     * Liest Argumente wie Maven: {@code -Pa,b}, {@code -P a}, {@code --activate-profiles=a}, {@code !a}/{@code -a}
     * schaltet ab, {@code +a} an; {@code -Dk=v}, {@code -D k=v}, {@code --define k}. Spätere Angaben gewinnen.
     * Alles andere (Goals, Schalter) wird übergangen.
     */
    public RunArguments parse(List<String> tokens) {
        if (tokens == null) {
            return this;
        }
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t == null) {
                continue;
            }
            t = t.trim();
            String value = null;
            boolean profile = false;
            boolean define = false;
            if (t.equals("-P") || t.equals("--activate-profiles")) {
                profile = true;
                value = i + 1 < tokens.size() ? tokens.get(++i) : null;
            } else if (t.startsWith("--activate-profiles=")) {
                profile = true;
                value = t.substring("--activate-profiles=".length());
            } else if (t.startsWith("-P")) {
                profile = true;
                value = t.substring(2);
            } else if (t.equals("-D") || t.equals("--define")) {
                define = true;
                value = i + 1 < tokens.size() ? tokens.get(++i) : null;
            } else if (t.startsWith("--define=")) {
                define = true;
                value = t.substring("--define=".length());
            } else if (t.startsWith("-D")) {
                define = true;
                value = t.substring(2);
            }
            if (value == null) {
                continue;
            }
            if (profile) {
                profiles(value);
            } else if (define) {
                define(value);
            }
        }
        return this;
    }

    /** Liest eine Kommandozeile, Anführungszeichen wie in der Shell (ohne Escapes). */
    public RunArguments parse(String commandLine) {
        return commandLine == null ? this : parse(split(commandLine));
    }

    /** Profil an- ({@code true}) oder abschalten; wie die Profil-Liste einer Run-Konfiguration. */
    public RunArguments profile(String id, boolean active) {
        if (id != null && !id.trim().isEmpty()) {
            String p = id.trim();
            (active ? inactiveProfiles : activeProfiles).remove(p);
            (active ? activeProfiles : inactiveProfiles).add(p);
        }
        return this;
    }

    public RunArguments property(String name, String value) {
        if (name != null && !name.trim().isEmpty()) {
            properties.put(name.trim(), value == null ? "true" : value);
        }
        return this;
    }

    /** Ist ein Profil ausdrücklich an- oder abgeschaltet? {@code null}: weder noch. */
    public Boolean explicit(String id) {
        return activeProfiles.contains(id) ? Boolean.TRUE : inactiveProfiles.contains(id) ? Boolean.FALSE : null;
    }

    /** Für den Schnappschuss: aktive Profile sortiert, abgeschaltete mit {@code !} davor. */
    public List<String> profileList() {
        List<String> out = new ArrayList<String>(activeProfiles);
        Collections.sort(out);
        List<String> off = new ArrayList<String>();
        for (String p : inactiveProfiles) {
            off.add("!" + p);
        }
        Collections.sort(off);
        out.addAll(off);
        return out;
    }

    /** Gegenstück zu {@link #profileList()}. */
    public static RunArguments ofProfileList(List<String> list) {
        RunArguments a = new RunArguments();
        if (list != null) {
            for (String p : list) {
                if (p.startsWith("!")) {
                    a.profile(p.substring(1), false);
                } else {
                    a.profile(p, true);
                }
            }
        }
        return a;
    }

    private void profiles(String value) {
        for (String part : value.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("!") || p.startsWith("-")) {
                profile(p.substring(1), false);
            } else if (p.startsWith("+")) {
                profile(p.substring(1), true);
            } else {
                profile(p, true);
            }
        }
    }

    private void define(String value) {
        int eq = value.indexOf('=');
        if (eq < 0) {
            property(value, "true");
        } else {
            property(value.substring(0, eq), value.substring(eq + 1));
        }
    }

    static List<String> split(String commandLine) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        char quote = 0;
        boolean any = false;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    sb.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (sb.length() > 0 || any) {
                    out.add(sb.toString());
                    sb.setLength(0);
                    any = false;
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0 || any) {
            out.add(sb.toString());
        }
        return out;
    }
}
