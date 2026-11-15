package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Vergleicht den letzten grünen Build mit dem aktuellen: „Was hat sich geändert?“
 *
 * <p>Gleiche Änderungen in mehreren Modulen werden zu einer Zeile zusammengefasst. Dependencies
 * werden nur dann auf neu/entfernt geprüft, wenn beide Schnappschüsse gleich tief aufgelöst sind –
 * sonst entstünde Rauschen, nur weil ein Lauf (etwa {@code validate}) keine transitive Auflösung hatte.
 */
public final class SnapshotDiff {

    /**
     * Warum der Vergleich nicht alles sieht. Die IDE liest das Modell der Module aus ihrem Maven-Import, nicht
     * aus dem Lauf; weichen die {@code -P}/{@code -D} des Laufs davon ab, fehlt deren Wirkung auf transitive
     * Dependencies, Plugins und Properties.
     */
    public enum Limitation {
        /** Alles verglichen, was beide messen. */
        NONE,
        /** Grüner und roter Lauf hatten verschiedene Profile oder {@code -D}-Properties. */
        RUN_ARGUMENTS,
        /** Gleiche Argumente, aber andere Profile als der Import der IDE. */
        IMPORT_PROFILES
    }

    private final BuildSnapshot green;
    private final BuildSnapshot current;
    private final List<Change> changes;
    private final Limitation limitation;

    private SnapshotDiff(BuildSnapshot green, BuildSnapshot current, List<Change> changes, Limitation limitation) {
        this.green = green;
        this.current = current;
        this.changes = changes;
        this.limitation = limitation;
    }

    public static SnapshotDiff compare(BuildSnapshot green, BuildSnapshot current) {
        List<Change> out = new ArrayList<Change>();
        // IDE- und Extension-Schnappschüsse messen nicht alles gleich (Mirrors kennt nur die Extension,
        // die IDE löst alle Scopes auf). Über Herkünfte hinweg zählt deshalb nur, was beide gleich messen.
        boolean sameOrigin = green.getOrigin() != null && green.getOrigin().equals(current.getOrigin());
        String gj = green.getJavaVersion();
        String cj = current.getJavaVersion();
        if (sameOrigin || BuildSetup.javaMajor(gj) != BuildSetup.javaMajor(cj)) {
            env(out, Change.Area.JDK, gj, cj);
        }
        env(out, Change.Area.MAVEN, green.getMavenVersion(), current.getMavenVersion());
        if (sameOrigin && green.getOffline() != null && current.getOffline() != null) {
            env(out, Change.Area.OFFLINE, onOff(green.getOffline()), onOff(current.getOffline()));
        }
        if (sameOrigin) {
            env(out, Change.Area.SETTINGS, green.getUserSettings(), current.getUserSettings());
        }
        String gp = joinSorted(green.getActiveProfiles());
        String cp = joinSorted(current.getActiveProfiles());
        if (!gp.equals(cp)) {
            out.add(new Change(Change.Area.PROFILES, null, gp.isEmpty() ? Texts.t("changes.none") : gp, cp.isEmpty() ? Texts.t("changes.none") : cp));
        }
        boolean propertiesDiffer = false;
        if (green.getRunProperties() != null && current.getRunProperties() != null) {
            int before = out.size();
            map(out, Change.Area.RUN_PROPERTY, null, prefixed(green.getRunProperties()), prefixed(current.getRunProperties()), true);
            propertiesDiffer = out.size() > before;
        }
        if (sameOrigin) {
            map(out, Change.Area.MIRRORS, null, green.getMirrors(), current.getMirrors(), true);
        }

        Set<String> all = new TreeSet<String>(green.getModules().keySet());
        all.addAll(current.getModules().keySet());
        for (String key : all) {
            BuildSnapshot.Module g = green.getModules().get(key);
            BuildSnapshot.Module c = current.getModules().get(key);
            if (g == null || c == null) {
                // Ein Lauf mit -pl baut nur einen Teil des Reactors: fehlende Module sind kein Befund.
                if (g == null) {
                    add(out, new Change(Change.Area.MODULES, key, null, c.getVersion()), null);
                }
                continue;
            }
            List<Change> local = new ArrayList<Change>();
            if (!eq(g.getParent(), c.getParent())) {
                local.add(parentChange(g.getParent(), c.getParent()));
            }
            map(local, Change.Area.PROPERTY, null, g.getVersionProperties(), c.getVersionProperties(), false);
            map(local, Change.Area.PLUGIN, null, g.getPlugins(), c.getPlugins(), false);
            boolean comparable = sameOrigin && g.isDependenciesResolved() == c.isDependenciesResolved();
            dependencies(local, g, c, comparable);
            declared(local, g, c);
            if (local.isEmpty() && sameOrigin && g.getEffectivePomHash() != null && c.getEffectivePomHash() != null
                    && !g.getEffectivePomHash().equals(c.getEffectivePomHash())) {
                local.add(new Change(Change.Area.EFFECTIVE_POM, key, Texts.t("changes.green.state"), Texts.t("changes.changed")));
            }
            for (Change ch : local) {
                add(out, ch, key);
            }
        }
        Collections.sort(out, new Comparator<Change>() {
            @Override
            public int compare(Change a, Change b) {
                int c = a.getArea().compareTo(b.getArea());
                if (c != 0) {
                    return c;
                }
                c = b.getModules().size() - a.getModules().size();
                return c != 0 ? c : String.valueOf(a.getSubject()).compareTo(String.valueOf(b.getSubject()));
            }
        });
        return new SnapshotDiff(green, current, out, limitation(green, current, !gp.equals(cp) || propertiesDiffer));
    }

    private static Limitation limitation(BuildSnapshot green, BuildSnapshot current, boolean argumentsDiffer) {
        if (!green.isModelFromImport() && !current.isModelFromImport()) {
            return Limitation.NONE;
        }
        if (argumentsDiffer) {
            return Limitation.RUN_ARGUMENTS;
        }
        return deviatesFromImport(green) || deviatesFromImport(current) ? Limitation.IMPORT_PROFILES : Limitation.NONE;
    }

    private static boolean deviatesFromImport(BuildSnapshot s) {
        return s.isModelFromImport() && !joinSorted(s.getImportProfiles()).equals(joinSorted(s.getActiveProfiles()));
    }

    public BuildSnapshot getGreen() { return green; }

    public BuildSnapshot getCurrent() { return current; }

    public List<Change> getChanges() { return Collections.unmodifiableList(changes); }

    public boolean isEmpty() { return changes.isEmpty(); }

    /** Was der Vergleich nicht sehen konnte; {@link Limitation#NONE}, wenn nichts fehlt. */
    public Limitation getLimitation() { return limitation; }

    /** Hinweis zur {@link #getLimitation()} als Klartext, {@code null} ohne Einschränkung. */
    public String limitationText() {
        switch (limitation) {
            case RUN_ARGUMENTS:
                return Texts.t("changes.limited.arguments");
            case IMPORT_PROFILES:
                String imported = joinSorted(importProfiles());
                return Texts.t("changes.limited.import", imported.isEmpty() ? Texts.t("changes.none") : imported);
            default:
                return null;
        }
    }

    /** Profile des Imports, mit dem die Module gelesen wurden (bevorzugt der des roten Laufs). */
    public List<String> importProfiles() {
        List<String> p = current.getImportProfiles() != null ? current.getImportProfiles() : green.getImportProfiles();
        return p == null ? Collections.<String>emptyList() : Collections.unmodifiableList(p);
    }

    /** Höchstens {@code max} Zeilen, danach „… und N weitere“. */
    public List<String> lines(int max) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < changes.size() && i < max; i++) {
            out.add(changes.get(i).text());
        }
        if (changes.size() > max) {
            out.add(Texts.t("changes.more", changes.size() - max));
        }
        return out;
    }

    /** Überschrift mit Zeitpunkt des grünen Builds, z. B. „Seit dem letzten grünen Build (2026-11-12 08:14)“. */
    public String headline() {
        String at = green.getCreatedAt();
        if (at != null && at.length() >= 16) {
            at = at.substring(0, 10) + " " + at.substring(11, 16);
        }
        return Texts.t("changes.headline") + (at == null ? "" : " (" + at + ")");
    }

    // ---------------------------------------------------------------- intern

    private static Change parentChange(String before, String after) {
        // "g:a:v" → bei gleichem g:a nur die Version zeigen
        if (before != null && after != null) {
            int b = before.lastIndexOf(':');
            int a = after.lastIndexOf(':');
            if (b > 0 && a > 0 && before.substring(0, b).equals(after.substring(0, a))) {
                return new Change(Change.Area.PARENT, before.substring(0, b), before.substring(b + 1), after.substring(a + 1));
            }
        }
        return new Change(Change.Area.PARENT, null, before, after);
    }

    private static void env(List<Change> out, Change.Area area, String before, String after) {
        if (before != null && after != null && !before.equals(after)) {
            out.add(new Change(area, null, before, after));
        }
    }

    private static void map(List<Change> out, Change.Area area, String module,
                            Map<String, String> before, Map<String, String> after, boolean addedRemoved) {
        Set<String> keys = new TreeSet<String>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            String b = before.get(k);
            String a = after.get(k);
            if (eq(a, b) || (!addedRemoved && (a == null || b == null))) {
                continue;
            }
            add(out, new Change(area, k, b, a), module);
        }
    }

    /**
     * Dependencies vergleichen. Neu/weg nur, wenn beide Läufe gleich aufgelöst haben und beide den Scope der
     * Dependency gesehen haben – ein grünes {@code compile} kennt keine test-Dependencies, ein rotes {@code verify}
     * schon; das ist keine Änderung.
     */
    private static void dependencies(List<Change> out, BuildSnapshot.Module g, BuildSnapshot.Module c, boolean comparable) {
        Set<String> keys = new TreeSet<String>(g.getDependencies().keySet());
        keys.addAll(c.getDependencies().keySet());
        for (String k : keys) {
            String b = g.getDependencies().get(k);
            String a = c.getDependencies().get(k);
            if (eq(a, b)) {
                continue;
            }
            if (a == null || b == null) {
                String scope = g.getDependencyScopes().get(k);
                if (scope == null) {
                    scope = c.getDependencyScopes().get(k);
                }
                if (!comparable || !g.covers(scope) || !c.covers(scope)) {
                    continue;
                }
            }
            add(out, new Change(Change.Area.DEPENDENCY, k, b, a), null);
        }
    }

    /**
     * Versionen, die das Modul unter den Profilen/{@code -D} des jeweiligen Laufs selbst deklariert
     * ({@link DeclaredVersions}). Ergänzt nur, was der Vergleich der (importierten) Dependencies nicht schon nennt;
     * nur Versionswechsel – fehlt ein Eintrag, war er im Lauf nicht sicher ablesbar.
     */
    private static void declared(List<Change> out, BuildSnapshot.Module g, BuildSnapshot.Module c) {
        if (g.getDeclaredDependencies().isEmpty() || c.getDeclaredDependencies().isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : g.getDeclaredDependencies().entrySet()) {
            String after = c.getDeclaredDependencies().get(e.getKey());
            if (after == null || after.equals(e.getValue()) || mentions(out, e.getKey())) {
                continue;
            }
            add(out, new Change(Change.Area.DEPENDENCY, e.getKey(), e.getValue(), after), null);
        }
    }

    private static boolean mentions(List<Change> out, String dependency) {
        for (Change ch : out) {
            if (ch.getArea() == Change.Area.DEPENDENCY && dependency.equals(ch.getSubject())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> prefixed(Map<String, String> properties) {
        Map<String, String> out = new java.util.TreeMap<String, String>();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            out.put("-D" + e.getKey(), e.getValue());
        }
        return out;
    }

    /** Fügt hinzu oder hängt das Modul an eine gleiche Änderung an. */
    private static void add(List<Change> out, Change change, String module) {
        for (Change existing : out) {
            if (existing.sameAs(change)) {
                existing.addModule(module);
                return;
            }
        }
        change.addModule(module);
        out.add(change);
    }

    private static String onOff(Boolean b) {
        return Texts.t(b ? "changes.on" : "changes.off");
    }

    private static String joinSorted(List<String> list) {
        List<String> copy = new ArrayList<String>(list);
        Collections.sort(copy);
        StringBuilder sb = new StringBuilder();
        for (String s : copy) {
            sb.append(sb.length() == 0 ? "" : ", ").append(s);
        }
        return sb.toString();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
