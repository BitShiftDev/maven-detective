package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath.Entry;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modul A3 – doppelte Klassen: Dieselbe Klasse liegt in mehreren JARs eines Moduls, auch unter
 * verschiedenen Koordinaten (typisch {@code commons-logging} neben {@code jcl-over-slf4j},
 * {@code javax.xml.bind:jaxb-api} neben {@code jakarta.xml.bind-api} 2.x). Welche geladen wird,
 * entscheidet die Classpath-Reihenfolge – bei abweichendem Inhalt die Quelle von
 * {@code NoSuchMethodError} und {@code ClassCastException}, die nur in manchen Umgebungen auftreten.
 *
 * <p>Je Modul werden die Einträge mit gemeinsamen Klassen zu einem {@link Clash} gruppiert. Die
 * Empfehlung, welches JAR weg soll, kommt aus einer kleinen Tabelle bekannter Paare, sonst aus dem
 * Inhalt: Ein JAR, dessen Klassen alle auch in einem anderen liegen, kann ohne Verlust weg; bei
 * gleichem Umfang das, das heute verliert (die Laufzeit ändert sich dann nicht). Der Quick-Fix
 * {@code add-exclusion} setzt die Exclusion bei jeder direkten Dependency, über die das JAR kommt.
 */
public final class DuplicateClasses {

    private static final int MAX_EXAMPLES = 5;

    /** Bekanntes Paar: {@code drop} gehört weg, wenn {@code keep} (der Nachfolger oder die Brücke) da ist. */
    static final class Known {
        final String[] drop;
        final String[] keep;
        /** Schlüssel in {@code texts.properties}. */
        final String hint;

        Known(String[] drop, String[] keep, String hint) {
            this.drop = drop;
            this.keep = keep;
            this.hint = hint;
        }

        static boolean matches(String[] patterns, Entry e) {
            for (String p : patterns) {
                if (p.equals(e.ga())) {
                    return true;
                }
            }
            return false;
        }
    }

    static final List<Known> KNOWN = Collections.unmodifiableList(Arrays.asList(
            new Known(new String[] {"commons-logging:commons-logging"},
                    new String[] {"org.slf4j:jcl-over-slf4j", "org.springframework:spring-jcl"},
                    "dup.known.commons.logging"),
            new Known(new String[] {"log4j:log4j"}, new String[] {"org.slf4j:log4j-over-slf4j"},
                    "dup.known.log4j"),
            new Known(new String[] {"javax.xml.bind:jaxb-api"}, new String[] {"jakarta.xml.bind:jakarta.xml.bind-api"},
                    "dup.known.jaxb"),
            new Known(new String[] {"javax.activation:activation", "javax.activation:javax.activation-api"},
                    new String[] {"jakarta.activation:jakarta.activation-api", "com.sun.activation:jakarta.activation"},
                    "dup.known.activation"),
            new Known(new String[] {"javax.annotation:javax.annotation-api", "javax.annotation:jsr250-api"},
                    new String[] {"jakarta.annotation:jakarta.annotation-api"},
                    "dup.known.annotation"),
            new Known(new String[] {"javax.validation:validation-api"}, new String[] {"jakarta.validation:jakarta.validation-api"},
                    "dup.known.validation"),
            new Known(new String[] {"javax.servlet:servlet-api"}, new String[] {"javax.servlet:javax.servlet-api", "org.apache.tomcat.embed:tomcat-embed-core"},
                    "dup.known.servlet"),
            new Known(new String[] {"javax.ws.rs:jsr311-api"}, new String[] {"javax.ws.rs:javax.ws.rs-api", "jakarta.ws.rs:jakarta.ws.rs-api"},
                    "dup.known.jsr311"),
            new Known(new String[] {"com.google.collections:google-collections"}, new String[] {"com.google.guava:guava"},
                    "dup.known.google.collections"),
            new Known(new String[] {"asm:asm"}, new String[] {"org.ow2.asm:asm"},
                    "dup.known.asm"),
            new Known(new String[] {"org.hamcrest:hamcrest-core", "org.hamcrest:hamcrest-library"}, new String[] {"org.hamcrest:hamcrest"},
                    "dup.known.hamcrest"),
            new Known(new String[] {"org.bouncycastle:bcprov-jdk15on"}, new String[] {"org.bouncycastle:bcprov-jdk18on"},
                    "dup.known.bcprov"),
            new Known(new String[] {"org.bouncycastle:bcpkix-jdk15on"}, new String[] {"org.bouncycastle:bcpkix-jdk18on"},
                    "dup.known.bcpkix"),
            new Known(new String[] {"stax:stax-api"}, new String[] {"javax.xml.stream:stax-api"},
                    "dup.known.stax")));

    /** JARs eines Moduls mit gemeinsamen Klassen. */
    public static final class Clash {
        private final ModuleClasspath module;
        private final List<Entry> jars;
        private final List<String> classes;
        private final int differing;
        private Entry drop;
        private String reason;
        private String hint;

        Clash(ModuleClasspath module, List<Entry> jars, List<String> classes, int differing) {
            this.module = module;
            this.jars = jars;
            this.classes = classes;
            this.differing = differing;
        }

        public ModuleClasspath getModule() { return module; }
        /** Die beteiligten Einträge in Classpath-Reihenfolge – der erste gewinnt (bei Maven 3, Tiefe zuerst). */
        public List<Entry> getJars() { return Collections.unmodifiableList(jars); }
        /** Gemeinsame Klassen, sortiert. */
        public List<String> getClasses() { return Collections.unmodifiableList(classes); }
        /** Wie viele der gemeinsamen Klassen sich im Inhalt unterscheiden. */
        public int getDiffering() { return differing; }
        public boolean isIdentical() { return differing == 0; }

        /** Betrifft den Laufzeit-Classpath (mindestens zwei Einträge nicht nur für Tests). */
        public boolean isMain() {
            int n = 0;
            for (Entry e : jars) {
                if (e.isMain()) {
                    n++;
                }
            }
            return n >= 2;
        }

        /** Empfohlen zu entfernen oder {@code null}, wenn das eine Entscheidung braucht. */
        public Entry getDrop() { return drop; }
        /** Warum gerade dieses JAR. */
        public String getReason() { return reason; }
        /** Wissen zum Paar aus der Tabelle oder {@code null}. */
        public String getHint() { return hint; }

        /** Gemeinsame Pakete, sortiert. */
        public List<String> packages() {
            List<String> out = new ArrayList<String>();
            for (String c : classes) {
                int dot = c.lastIndexOf('.');
                String p = dot < 0 ? "(Standardpaket)" : c.substring(0, dot);
                if (!out.contains(p)) {
                    out.add(p);
                }
            }
            Collections.sort(out);
            return out;
        }

        /** Befund in einem Satz. */
        public String message() {
            StringBuilder sb = new StringBuilder();
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < jars.size(); i++) {
                names.append(i == 0 ? "" : i == jars.size() - 1 ? " " + Texts.t("and") + " " : ", ").append(jars.get(i).label());
            }
            sb.append(Texts.t("dup.message.classes", classes.size(), names)).append(" – ");
            if (isIdentical()) {
                sb.append(Texts.t("dup.message.identical"));
            } else {
                sb.append(Texts.t("dup.message.differing", differing == classes.size() ? Texts.t("dup.all") : String.valueOf(differing),
                        jars.get(0).label()));
            }
            if (!isMain()) {
                sb.append(" (").append(Texts.t("dup.message.test")).append(")");
            }
            return sb.append('.').toString();
        }

        /** Empfehlung als Satz. */
        public String recommendation() {
            if (drop == null) {
                return reason;
            }
            return Texts.t("dup.remove", drop.label(), reason);
        }

        /** Quick-Fixes für die Empfehlung; leer, wenn keine. */
        public List<FixAction> fixes() {
            return drop == null ? Collections.<FixAction>emptyList() : fixesFor(drop);
        }

        /**
         * Quick-Fixes, um {@code e} aus dem Classpath des Moduls zu nehmen: je direkter Dependency, über
         * die es kommt, eine {@code add-exclusion}; ist es selbst direkt deklariert, ein Sprung zur
         * Deklaration ({@code open-file}), denn dann muss die Dependency raus.
         */
        public List<FixAction> fixesFor(Entry e) {
            List<FixAction> out = new ArrayList<FixAction>();
            if (e.isSelf()) {
                return out;
            }
            EffectiveModel model = module.getModel();
            Declared own = model.declared(e.getGroupId(), e.getArtifactId());
            if (e.isDirect() || own != null) {
                if (own != null) {
                    out.add(FixAction.of(FixAction.OPEN_FILE, Texts.t("dup.fix.declared", e.ga()),
                            "file", own.declaredIn.getFile().toString(), "line", String.valueOf(own.dep.getLine())));
                }
                return out;
            }
            for (String direct : module.directDependenciesBringing(e.ga())) {
                int colon = direct.indexOf(':');
                Declared d = model.declared(direct.substring(0, colon), direct.substring(colon + 1));
                if (d == null) {
                    continue; // z. B. aus einem Profil – dafür gibt es keinen sicheren Ort
                }
                boolean inherited = !d.declaredIn.getFile().equals(module.getPom().getFile());
                out.add(FixAction.of(FixAction.ADD_EXCLUSION,
                        Texts.t("dup.fix.exclusion", e.ga(), direct)
                                + (inherited ? " (" + Texts.t("dup.fix.inherited", d.declaredIn.getArtifactId()) + ")" : ""),
                        "file", d.declaredIn.getFile().toString(), "artifact", direct, "exclusion", e.ga()));
            }
            return out;
        }
    }

    /** Ergebnis für ein Modul. */
    public static final class ModuleResult {
        private final ModuleClasspath classpath;
        private final List<Clash> clashes = new ArrayList<Clash>();
        private final List<Entry> unreadable = new ArrayList<Entry>();

        ModuleResult(ModuleClasspath classpath) {
            this.classpath = classpath;
        }

        public ModuleClasspath getClasspath() { return classpath; }
        public List<Clash> getClashes() { return Collections.unmodifiableList(clashes); }
        /** Einträge, deren JAR lokal fehlt oder kaputt ist – darin wurde nicht gesucht. */
        public List<Entry> getUnreadable() { return Collections.unmodifiableList(unreadable); }
    }

    private final List<ModuleResult> modules;

    private DuplicateClasses(List<ModuleResult> modules) {
        this.modules = modules;
    }

    public List<ModuleResult> getModules() { return Collections.unmodifiableList(modules); }

    /** Alle Funde über alle Module, Laufzeit-Classpath und verschiedener Inhalt zuerst. */
    public List<Clash> clashes() {
        List<Clash> out = new ArrayList<Clash>();
        for (ModuleResult m : modules) {
            out.addAll(m.clashes);
        }
        return out;
    }

    /** Funde mit Empfehlung (für {@code --exclude}). */
    public List<Clash> fixable() {
        List<Clash> out = new ArrayList<Clash>();
        for (Clash c : clashes()) {
            if (!c.fixes().isEmpty() && FixAction.ADD_EXCLUSION.equals(c.fixes().get(0).getType())) {
                out.add(c);
            }
        }
        return out;
    }

    /** Wurde irgendein Classpath ohne Baum nachgebaut? */
    public boolean isEstimated() {
        for (ModuleResult m : modules) {
            if (!m.classpath.isFromTree()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Alle Module prüfen. POMs mit {@code packaging pom} werden übersprungen.
     *
     * @param trees aufgelöste Bäume je {@code groupId:artifactId} des Moduls (darf leer sein)
     * @param index JAR-Inhalte; die IDE reicht einen langlebigen herein
     */
    public static DuplicateClasses build(PomLocator locator, List<PomFile> poms, Map<String, DepNode> trees, JarIndex index,
                                         Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        Map<String, PomFile> reactor = new HashMap<String, PomFile>();
        for (PomFile p : poms) {
            reactor.put(p.getGroupId() + ":" + p.getArtifactId(), p);
        }
        List<ModuleResult> out = new ArrayList<ModuleResult>();
        for (PomFile pom : poms) {
            if ("pom".equals(pom.getPackaging())) {
                continue;
            }
            DepNode tree = trees == null ? null : trees.get(pom.getGroupId() + ":" + pom.getArtifactId());
            out.add(analyze(ModuleClasspath.of(locator, pom, tree, reactor), index, cancel));
        }
        return new DuplicateClasses(out);
    }

    /** Ein Modul mit fertigem Classpath prüfen. */
    public static ModuleResult analyze(ModuleClasspath cp, JarIndex index, Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        ModuleResult result = new ModuleResult(cp);
        List<Entry> read = new ArrayList<Entry>();
        List<JarIndex.Contents> contents = new ArrayList<JarIndex.Contents>();
        for (Entry e : cp.getEntries()) {
            cancel.check();
            if (e.getFile() == null) {
                result.unreadable.add(e);
                continue;
            }
            JarIndex.Contents c;
            try {
                c = index.read(e.getFile());
            } catch (IOException ex) {
                result.unreadable.add(e);
                continue;
            } catch (RuntimeException ex) {
                result.unreadable.add(e);
                continue;
            }
            read.add(e);
            contents.add(c);
        }

        // Klasse → Einträge (Indizes in Classpath-Reihenfolge), nur wo es mehr als einen gibt
        Map<String, int[]> owners = new HashMap<String, int[]>();
        Map<String, List<Integer>> multi = new HashMap<String, List<Integer>>();
        for (int j = 0; j < contents.size(); j++) {
            JarIndex.Contents c = contents.get(j);
            for (int i = 0; i < c.size(); i++) {
                String name = c.name(i);
                int[] first = owners.get(name);
                if (first == null) {
                    owners.put(name, new int[] {j});
                    continue;
                }
                List<Integer> l = multi.get(name);
                if (l == null) {
                    l = new ArrayList<Integer>();
                    l.add(first[0]);
                    multi.put(name, l);
                }
                l.add(j);
            }
        }
        if (multi.isEmpty()) {
            return result;
        }

        int[] duplicated = new int[contents.size()];
        Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        List<String> names = new ArrayList<String>(multi.keySet());
        Collections.sort(names);
        for (String name : names) {
            List<Integer> l = multi.get(name);
            StringBuilder key = new StringBuilder();
            for (Integer j : l) {
                duplicated[j]++;
                key.append(j).append(',');
            }
            List<String> g = groups.get(key.toString());
            if (g == null) {
                g = new ArrayList<String>();
                groups.put(key.toString(), g);
            }
            g.add(name);
        }

        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            List<Integer> idx = new ArrayList<Integer>();
            for (String s : g.getKey().split(",")) {
                idx.add(Integer.valueOf(s));
            }
            List<Entry> jars = new ArrayList<Entry>();
            for (Integer j : idx) {
                jars.add(read.get(j));
            }
            int differing = 0;
            for (String name : g.getValue()) {
                long crc = contents.get(idx.get(0)).crc(name);
                for (int k = 1; k < idx.size(); k++) {
                    if (contents.get(idx.get(k)).crc(name) != crc) {
                        differing++;
                        break;
                    }
                }
            }
            Clash clash = new Clash(cp, jars, g.getValue(), differing);
            recommend(clash, idx, contents, duplicated);
            result.clashes.add(clash);
        }
        Collections.sort(result.clashes, new java.util.Comparator<Clash>() {
            @Override
            public int compare(Clash a, Clash b) {
                if (a.isMain() != b.isMain()) {
                    return a.isMain() ? -1 : 1;
                }
                if (a.isIdentical() != b.isIdentical()) {
                    return a.isIdentical() ? 1 : -1;
                }
                return b.classes.size() - a.classes.size();
            }
        });
        return result;
    }

    /** Welches JAR soll weg? Tabelle, sonst Inhalt; sonst bleibt es eine Entscheidung. */
    private static void recommend(Clash clash, List<Integer> idx, List<JarIndex.Contents> contents, int[] duplicated) {
        for (Known k : KNOWN) {
            Entry drop = null;
            boolean keep = false;
            for (Entry e : clash.jars) {
                if (Known.matches(k.drop, e) && drop == null) {
                    drop = e;
                } else if (Known.matches(k.keep, e)) {
                    keep = true;
                }
            }
            if (drop != null && keep) {
                clash.drop = drop;
                clash.hint = Texts.t(k.hint);
                clash.reason = Texts.t("dup.reason.known", clash.hint);
                return;
            }
        }
        // Einträge, deren Klassen alle auch woanders liegen: die können ohne Verlust weg
        List<Integer> covered = new ArrayList<Integer>();
        for (int k = 0; k < idx.size(); k++) {
            int j = idx.get(k);
            if (!clash.jars.get(k).isSelf() && duplicated[j] == contents.get(j).size()) {
                covered.add(k);
            }
        }
        if (covered.isEmpty()) {
            Entry winner = clash.jars.get(0);
            clash.reason = Texts.t("dup.reason.own.classes", clash.jars.size(), winner.label());
            return;
        }
        // bei mehreren: lieber ein transitives (Exclusion möglich) als ein direktes, sonst das, das heute verliert
        Integer pick = null;
        for (int i = covered.size() - 1; i >= 0; i--) {
            int k = covered.get(i);
            if (pick == null || !clash.jars.get(k).isDirect() && clash.jars.get(pick).isDirect()) {
                pick = k;
            }
        }
        Entry drop = clash.jars.get(pick);
        clash.drop = drop;
        if (pick == 0 && !clash.isIdentical()) {
            clash.reason = Texts.t("dup.reason.winner");
        } else if (clash.isIdentical()) {
            clash.reason = Texts.t("dup.reason.identical", other(clash, drop).label());
        } else {
            clash.reason = Texts.t("dup.reason.loser", other(clash, drop).label());
        }
    }

    private static Entry other(Clash clash, Entry not) {
        for (Entry e : clash.jars) {
            if (e != not) {
                return e;
            }
        }
        return not;
    }

    /** Konsolenausgabe; mit {@code all} alle Klassen statt Beispielen. */
    public List<String> lines(boolean all) {
        List<String> out = new ArrayList<String>();
        out.add(summary());
        if (isEstimated()) {
            out.add(Texts.t("deps.estimated"));
        }
        for (ModuleResult m : modules) {
            if (m.clashes.isEmpty() && m.unreadable.isEmpty()) {
                continue;
            }
            out.add("");
            out.add(Texts.t("dup.module", m.classpath.getName(), m.classpath.getEntries().size()));
            for (Clash c : m.clashes) {
                out.add("  " + pad(Texts.t(c.isIdentical() ? "dup.identical" : "dup.differing"), 11) + "  " + c.message());
                for (Entry e : c.jars) {
                    out.add("      " + (e == c.jars.get(0) ? "1. " : "   ") + e.label() + "  " + via(e));
                }
                out.add("      " + Texts.t("dup.packages") + " " + joinLimited(c.packages(), all ? Integer.MAX_VALUE : 3));
                out.add("      " + Texts.t("dup.classes") + " " + joinLimited(c.classes, all ? Integer.MAX_VALUE : MAX_EXAMPLES));
                out.add("      " + Texts.t("dup.recommendation") + " " + c.recommendation());
                for (FixAction f : c.fixes()) {
                    out.add("        → " + f.getLabel());
                }
            }
            if (!m.unreadable.isEmpty()) {
                List<String> l = new ArrayList<String>();
                for (Entry e : m.unreadable) {
                    l.add(e.gav());
                }
                out.add("  " + Texts.t("dup.unreadable") + " " + joinLimited(l, all ? Integer.MAX_VALUE : MAX_EXAMPLES));
            }
        }
        if (clashes().isEmpty()) {
            out.add("");
            out.add(Texts.t("dup.none"));
        }
        return out;
    }

    /** Markdown-Tabelle für Ticket oder Review. */
    public String markdown(boolean all) {
        StringBuilder sb = new StringBuilder("### ").append(Texts.t("dup.title")).append("\n\n").append(summary()).append("\n\n");
        List<Clash> list = clashes();
        if (list.isEmpty()) {
            return sb.append(Texts.t("dup.none")).append("\n").toString();
        }
        sb.append(Texts.t("dup.md.table")).append("\n|---|---|---|---|---|\n");
        for (Clash c : list) {
            List<String> jars = new ArrayList<String>();
            for (Entry e : c.jars) {
                jars.add("`" + e.label() + "`");
            }
            sb.append("| `").append(c.module.getName()).append("` | ").append(joinLimited(jars, Integer.MAX_VALUE)).append(" | ")
                    .append(c.classes.size()).append(" (").append(joinLimited(c.packages(), all ? Integer.MAX_VALUE : 2)).append(") | ")
                    .append(c.isIdentical() ? Texts.t("dup.identical") : c.differing + " " + Texts.t("dup.differing")).append(c.isMain() ? "" : ", " + Texts.t("dup.tests.only"))
                    .append(" | ").append(c.recommendation().replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private String summary() {
        int main = 0;
        int differ = 0;
        for (Clash c : clashes()) {
            if (c.isMain()) {
                main++;
            }
            if (!c.isIdentical()) {
                differ++;
            }
        }
        int n = clashes().size();
        return Texts.t("dup.summary", n, modules.size(), differ, main);
    }

    private static String via(Entry e) {
        if (e.isSelf()) {
            return "";
        }
        if (e.isDirect()) {
            return "(" + Texts.t("upgrade.direct") + ", " + e.getScope() + ")";
        }
        StringBuilder sb = new StringBuilder("(" + Texts.t("via") + " ");
        List<String> p = e.getPath();
        for (int i = 0; i < p.size() - 1; i++) {
            sb.append(i == 0 ? "" : " → ").append(p.get(i));
        }
        return sb.append(", ").append(e.getScope()).append(')').toString();
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String joinLimited(List<String> parts, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() && i < max; i++) {
            sb.append(i == 0 ? "" : ", ").append(parts.get(i));
        }
        if (parts.size() > max) {
            sb.append(" … (+").append(parts.size() - max).append(')');
        }
        return sb.toString();
    }
}
