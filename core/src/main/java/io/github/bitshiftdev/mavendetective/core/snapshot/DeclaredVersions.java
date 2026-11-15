package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.deps.PomFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Welche Version jedes Modul für seine <b>direkt deklarierten</b> Dependencies unter den Profilen und
 * {@code -D}-Properties eines bestimmten Laufs notiert – aus den Projekt-POMs, ohne Maven.
 *
 * <p>Die IDE liest die aufgelösten Dependencies aus ihrem Maven-Import; der kennt die {@code -P}/{@code -D} einer
 * Run-Konfiguration nicht. Was ein Profil an direkten Dependencies mitbringt, lässt sich aber sicher aus der POM
 * ablesen, solange das Profil nicht von der Umgebung abhängt. Unsichere Einträge fehlen lieber, als dass sie raten:
 * Profile mit {@code <activation>} (außer {@code activeByDefault}), Versionen ohne Angabe (Management) oder mit
 * nicht auflösbarem {@code ${…}}.
 *
 * <p>Profile werden wie bei Maven je POM injiziert (Profil gewinnt), dann wird vererbt (Kind gewinnt);
 * {@code -D} schlägt Properties der POMs.
 */
public final class DeclaredVersions {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");

    private DeclaredVersions() {
    }

    /**
     * Je Modul ({@code groupId:artifactId}) die deklarierten Versionen ({@code groupId:artifactId[:classifier]} →
     * Version). Parents werden nur innerhalb von {@code projectPoms} verfolgt; Module mit {@code packaging pom}
     * fehlen.
     */
    public static Map<String, Map<String, String>> of(Collection<PomFile> projectPoms, RunArguments run) {
        Map<String, PomFile> byGa = new HashMap<String, PomFile>();
        for (PomFile p : projectPoms) {
            byGa.put(p.getGroupId() + ":" + p.getArtifactId(), p);
        }
        Map<String, Map<String, String>> out = new TreeMap<String, Map<String, String>>();
        for (PomFile p : projectPoms) {
            if ("pom".equals(p.getPackaging())) {
                continue;
            }
            Map<String, String> declared = forModule(chain(p, byGa), run);
            if (!declared.isEmpty()) {
                out.put(p.getGroupId() + ":" + p.getArtifactId(), declared);
            }
        }
        return out;
    }

    /** Oberster Parent zuerst, das Modul zuletzt. */
    private static List<PomFile> chain(PomFile module, Map<String, PomFile> byGa) {
        List<PomFile> chain = new ArrayList<PomFile>();
        Set<String> seen = new HashSet<String>();
        PomFile p = module;
        while (p != null && seen.add(p.getGroupId() + ":" + p.getArtifactId())) {
            chain.add(p);
            p = p.hasParent() ? byGa.get(p.getParentGroupId() + ":" + p.getParentArtifactId()) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    private static Map<String, String> forModule(List<PomFile> chain, RunArguments run) {
        Map<String, String> versions = new LinkedHashMap<String, String>();   // Schlüssel → rohe Version
        Map<String, String> props = new HashMap<String, String>();
        Set<String> unsure = new HashSet<String>();
        boolean unsureProps = false;
        for (PomFile pom : chain) {
            Map<String, String> own = new LinkedHashMap<String, String>();
            for (PomFile.Dep d : pom.getDependencies()) {
                own.put(key(d), d.getVersion());
            }
            for (PomFile.Property p : pom.getProperties().values()) {
                props.put(p.getName(), p.getValue());
            }
            for (PomFile.Profile profile : pom.getProfiles()) {
                Boolean active = active(pom, profile, run);
                if (active == null) {
                    for (PomFile.Dep d : profile.getDependencies()) {
                        unsure.add(key(d));
                    }
                    unsureProps |= !profile.getProperties().isEmpty();
                    continue;
                }
                if (!active) {
                    continue;
                }
                for (PomFile.Dep d : profile.getDependencies()) {
                    String k = key(d);
                    if (d.getVersion() != null || !own.containsKey(k)) {
                        own.put(k, d.getVersion());
                    }
                }
                props.putAll(profile.getProperties());
            }
            versions.putAll(own);   // Kind gewinnt
        }
        PomFile module = chain.get(chain.size() - 1);
        props.put("project.version", module.getVersion());
        props.put("project.groupId", module.getGroupId());
        props.put("project.artifactId", module.getArtifactId());
        if (module.hasParent()) {
            props.put("project.parent.version", module.getParentVersion());
        }
        props.putAll(run.getProperties());
        Map<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, String> e : versions.entrySet()) {
            String raw = e.getValue();
            if (raw == null || unsure.contains(e.getKey()) || unsureProps && raw.contains("${")) {
                continue;
            }
            String v = interpolate(raw, props);
            if (v != null) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /**
     * Ist das Profil in diesem Lauf aktiv? {@code null}: hängt von der Umgebung ab. {@code -P} entscheidet immer;
     * {@code activeByDefault} gilt nur, solange kein anderes Profil derselben POM sicher aktiv ist.
     */
    static Boolean active(PomFile pom, PomFile.Profile profile, RunArguments run) {
        Boolean explicit = profile.getId() == null ? null : run.explicit(profile.getId());
        if (explicit != null) {
            return explicit;
        }
        switch (profile.getActivation()) {
            case NONE:
                return Boolean.FALSE;
            case CONDITION:
                return null;
            default:
                boolean unsure = false;
                for (PomFile.Profile other : pom.getProfiles()) {
                    if (other == profile) {
                        continue;
                    }
                    Boolean ex = other.getId() == null ? null : run.explicit(other.getId());
                    if (Boolean.TRUE.equals(ex)) {
                        return Boolean.FALSE;
                    }
                    unsure |= ex == null && other.getActivation() == PomFile.Profile.Activation.CONDITION;
                }
                return unsure ? null : Boolean.TRUE;
        }
    }

    private static String key(PomFile.Dep d) {
        String c = d.getClassifier();
        return d.getGroupId() + ":" + d.getArtifactId() + (c == null || c.isEmpty() ? "" : ":" + c);
    }

    /** {@code null}, wenn ein Ausdruck offen bleibt. */
    private static String interpolate(String raw, Map<String, String> props) {
        String v = raw;
        for (int round = 0; round < 10 && v.contains("${"); round++) {
            Matcher m = EXPRESSION.matcher(v);
            StringBuffer sb = new StringBuffer();
            boolean any = false;
            while (m.find()) {
                String name = m.group(1);
                String value = props.get(name.startsWith("pom.") ? "project." + name.substring(4) : name);
                if (value == null) {
                    return null;
                }
                any = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            v = sb.toString();
            if (!any) {
                break;
            }
        }
        return v.contains("${") || v.isEmpty() ? null : v;
    }
}
