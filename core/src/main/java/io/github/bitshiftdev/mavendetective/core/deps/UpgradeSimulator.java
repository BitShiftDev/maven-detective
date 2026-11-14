package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.ImportStep;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.PropertyOrigin;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.io.TextFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modul A6 – Upgrade-Simulator: „Was passiert, wenn ich X auf Version Y hebe?“ – bevor die POM
 * geändert wird.
 *
 * <p>Ablauf: Zuerst wird die Stelle gesucht, die die Version heute festlegt (direkte Angabe,
 * {@code dependencyManagement}, Property, BOM-Import, Parent; sonst ein neuer Eintrag im
 * {@code dependencyManagement} der obersten Projekt-POM). Diese Änderungen werden nur im Speicher
 * auf die POM-Texte angewendet ({@link PomEditor}, derselbe Weg wie beim späteren Übernehmen). Dann
 * baut der {@link DependencyResolver} für jedes Modul den Baum einmal mit den alten und einmal mit
 * den neuen POMs, und der Unterschied wird je Modul gezeigt: neue, wegfallende, gehobene und
 * gesenkte Artifacts, geänderte Scopes und neue Konflikte, bei denen eine verlangte höhere Version
 * verliert. Geht nie ins Netz – fehlen POMs der neuen Version lokal, sagt das Ergebnis welche
 * ({@link PomFetcher} lädt sie auf ausdrücklichen Wunsch).
 */
public final class UpgradeSimulator {

    private static final Pattern SINGLE_PROPERTY = Pattern.compile("^\\$\\{([^}]+)}$");

    /** Was gehoben werden soll. */
    public static final class Request {
        private final String groupId;
        private final String artifactId;
        private final String property;
        private final String version;
        private final boolean pin;

        private Request(String groupId, String artifactId, String property, String version, boolean pin) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.property = property;
            this.version = version;
            this.pin = pin;
        }

        /** Artifact, BOM oder Parent {@code groupId:artifactId} auf {@code version}. */
        public static Request artifact(String groupId, String artifactId, String version) {
            return new Request(groupId, artifactId, null, version, false);
        }

        /** Property {@code name} auf {@code value} – z. B. {@code jackson-bom.version} bei Spring Boot. */
        public static Request property(String name, String value) {
            return new Request(null, null, name, value, false);
        }

        /**
         * {@code groupId:artifactId:version} oder {@code property=wert}.
         *
         * @throws IllegalArgumentException bei anderem Format
         */
        public static Request parse(String spec) {
            String s = spec == null ? "" : spec.trim();
            int eq = s.indexOf('=');
            if (eq > 0 && eq < s.length() - 1) {
                String name = s.substring(0, eq).trim();
                if (name.startsWith("${") && name.endsWith("}")) {
                    name = name.substring(2, name.length() - 1);
                }
                return property(name, s.substring(eq + 1).trim());
            }
            String[] p = s.split(":");
            if (p.length != 3 || p[0].isEmpty() || p[1].isEmpty() || p[2].isEmpty()) {
                throw new IllegalArgumentException(Texts.t("upgrade.request.invalid", spec));
            }
            return artifact(p[0], p[1], p[2]);
        }

        /** Statt an der bisherigen Stelle immer im {@code dependencyManagement} der obersten Projekt-POM festlegen. */
        public Request pinned() {
            return new Request(groupId, artifactId, property, version, true);
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        /** Name der Property oder {@code null}. */
        public String getProperty() { return property; }
        public String getVersion() { return version; }
        public boolean isPin() { return pin; }
        public boolean isProperty() { return property != null; }

        public String ga() {
            return groupId + ":" + artifactId;
        }

        /** {@code g:a} bzw. {@code ${property}}. */
        public String label() {
            return isProperty() ? "${" + property + "}" : ga();
        }
    }

    /** Was gehoben wird: ein Artifact, ein importiertes BOM, ein Parent oder eine Property. */
    public enum Target { ARTIFACT, BOM, PARENT, PROPERTY }

    /** Eine geplante Änderung an einer POM – als Quick-Fix, damit IDE und CLI sie genauso anwenden. */
    public static final class Step {
        private final FixAction fix;
        private final String file;
        private final String reason;
        private String description;
        private int line = -1;

        Step(FixAction fix, String reason) {
            this.fix = fix;
            this.file = fix.param("file");
            this.reason = reason;
            this.description = fix.getLabel();
        }

        public FixAction getFix() { return fix; }
        public String getFile() { return file; }
        /** Was die Änderung tut (aus dem {@link PomEditor.Edit}), sonst das Label des Fixes. */
        public String getDescription() { return description; }
        /** Warum gerade diese Stelle. */
        public String getReason() { return reason; }
        /** Zeile in der heutigen Datei, an der geändert wird (bei neuen Einträgen die Einfügestelle); -1 = unbekannt. */
        public int getLine() { return line; }

        String key() {
            return file + "|" + fix.getType() + "|" + fix.param("property") + "|" + fix.param("section") + "|" + fix.param("artifact");
        }
    }

    /** Art einer Änderung im Baum. */
    public enum Kind { ADDED, REMOVED, UPGRADED, DOWNGRADED, SCOPE }

    /** Ein Artifact, das sich im Classpath eines Moduls ändert. */
    public static final class Delta {
        private final Kind kind;
        private final DepNode before;
        private final DepNode after;

        Delta(Kind kind, DepNode before, DepNode after) {
            this.kind = kind;
            this.before = before;
            this.after = after;
        }

        public Kind getKind() { return kind; }
        /** Knoten im alten Baum; {@code null} bei {@link Kind#ADDED}. */
        public DepNode getBefore() { return before; }
        /** Knoten im neuen Baum; {@code null} bei {@link Kind#REMOVED}. */
        public DepNode getAfter() { return after; }

        private DepNode any() {
            return after != null ? after : before;
        }

        public String getGroupId() { return any().getGroupId(); }
        public String getArtifactId() { return any().getArtifactId(); }
        public String ga() { return any().ga(); }
        public String getClassifier() { return any().getClassifier(); }

        public String label() {
            DepNode n = any();
            return n.ga() + (n.getClassifier() == null ? "" : ":" + n.getClassifier()) + ("jar".equals(n.getType()) ? "" : " (" + n.getType() + ")");
        }

        public String getBeforeVersion() { return before == null ? null : before.getVersion(); }
        public String getAfterVersion() { return after == null ? null : after.getVersion(); }

        /** Ein Satz: was sich ändert und über welchen Pfad. */
        public String message() {
            switch (kind) {
                case ADDED:
                    return Texts.t("upgrade.delta.added", label() + ":" + after.getVersion() + scopeNote(after), after.pathText());
                case REMOVED:
                    return Texts.t("upgrade.delta.removed", label() + ":" + before.getVersion() + scopeNote(before), before.pathText());
                case UPGRADED:
                    return label() + " " + before.getVersion() + " → " + after.getVersion() + via(after);
                case DOWNGRADED:
                    return Texts.t("upgrade.delta.downgraded", label(), before.getVersion(), after.getVersion()) + via(after);
                default:
                    return Texts.t("upgrade.delta.scope", label() + ":" + after.getVersion(), before.getScope(), after.getScope()) + via(after);
            }
        }

        private static String scopeNote(DepNode n) {
            return n.getScope() == null || "compile".equals(n.getScope()) ? "" : " (" + n.getScope() + ")";
        }

        private static String via(DepNode n) {
            return n.depth() <= 1 ? " (" + Texts.t("upgrade.direct") + ")" : " " + Texts.t("upgrade.via", n.pathText());
        }
    }

    /** Ein neuer Konflikt: Ein Artifact verlangt eine höhere Version, als im Classpath landet. */
    public static final class Risk {
        private final DepNode loser;

        Risk(DepNode loser) {
            this.loser = loser;
        }

        public String ga() { return loser.ga(); }
        /** Version, die verlangt wird (und verliert). */
        public String getWanted() { return loser.getVersion(); }
        /** Version, die gewinnt. */
        public String getChosen() { return loser.getRelatedVersion(); }
        /** Wer die höhere Version verlangt ({@code g:a:v}). */
        public String getRequester() { return loser.getParent() == null ? "?" : loser.getParent().gav(); }
        public String getPath() { return loser.pathText(); }
        /** Direkte Dependency des Moduls, über die der Verlierer kommt ({@code g:a}). */
        public String getDirect() {
            List<DepNode> path = loser.path();
            return path.isEmpty() ? ga() : path.get(0).ga();
        }

        public String message() {
            return Texts.t("upgrade.risk", ga(), getRequester(), getWanted(), getChosen());
        }

        String key() {
            return ga() + "|" + getWanted() + "|" + getChosen() + "|" + getRequester();
        }
    }

    /** Ergebnis für ein Modul. */
    public static final class ModuleResult {
        private final PomFile module;
        private final DepNode before;
        private final DepNode after;
        private final List<Delta> deltas = new ArrayList<Delta>();
        private final List<Risk> risks = new ArrayList<Risk>();
        private final List<String> missing = new ArrayList<String>();
        private String targetBefore;
        private String targetAfter;
        private String note;
        private String deviation;

        ModuleResult(PomFile module, DepNode before, DepNode after) {
            this.module = module;
            this.before = before;
            this.after = after;
        }

        public PomFile getModule() { return module; }
        public String getName() { return module.getArtifactId(); }
        /** Simulierter Baum mit den POMs von heute. */
        public DepNode getBefore() { return before; }
        /** Simulierter Baum nach der Änderung. */
        public DepNode getAfter() { return after; }
        public List<Delta> getDeltas() { return Collections.unmodifiableList(deltas); }
        /** Konflikte, die erst durch die Änderung entstehen. */
        public List<Risk> getRisks() { return Collections.unmodifiableList(risks); }
        /** Was für den neuen Baum lokal fehlt (und vorher nicht fehlte). */
        public List<String> getMissing() { return Collections.unmodifiableList(missing); }
        /** Version des gehobenen Artifacts vorher/nachher im Classpath (nur bei Artifacts), sonst {@code null}. */
        public String getTargetBefore() { return targetBefore; }
        public String getTargetAfter() { return targetAfter; }
        /** Warum die gewünschte Version hier nicht ankommt, sonst {@code null}. */
        public String getNote() { return note; }
        /** Hinweis, wenn die Simulation vom echten Baum (IDE/{@code dependency:tree}) abweicht, sonst {@code null}. */
        public String getDeviation() { return deviation; }

        public boolean isUnchanged() {
            return deltas.isEmpty() && risks.isEmpty();
        }

        /**
         * Dem neuen Baum fehlen POMs: Gesenkte, entfernte Artifacts und neue Konflikte werden dann nicht
         * gemeldet (sie können von der Lücke kommen), nur was steigt oder dazukommt.
         */
        public boolean isIncomplete() {
            return !missing.isEmpty();
        }

        public int count(Kind kind) {
            int n = 0;
            for (Delta d : deltas) {
                if (d.kind == kind) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Gesamtergebnis. */
    public static final class Result {
        private final Request request;
        private Target target = Target.ARTIFACT;
        private final List<Step> steps = new ArrayList<Step>();
        private final List<ModuleResult> modules = new ArrayList<ModuleResult>();
        private final List<String> problems = new ArrayList<String>();
        private final Set<String> missing = new LinkedHashSet<String>();
        private final Set<String> currentVersions = new LinkedHashSet<String>();

        Result(Request request) {
            this.request = request;
        }

        public Request getRequest() { return request; }
        public Target getTarget() { return target; }
        /** Die Änderungen an den POMs – noch nicht angewendet. */
        public List<Step> getSteps() { return Collections.unmodifiableList(steps); }
        public List<ModuleResult> getModules() { return Collections.unmodifiableList(modules); }
        /** Was sich nicht planen oder anwenden ließ. */
        public List<String> getProblems() { return Collections.unmodifiableList(problems); }
        /** Was für den neuen Baum lokal fehlt (Koordinaten zuerst); leer = Simulation vollständig. */
        public List<String> getMissing() { return new ArrayList<String>(missing); }
        /** Heutige Version(en) – bei Artifacts aus den Bäumen, sonst aus der geänderten Stelle. */
        public List<String> getCurrentVersions() { return new ArrayList<String>(currentVersions); }

        public boolean isComplete() {
            return missing.isEmpty();
        }

        /** Quick-Fixes für „Übernehmen“. */
        public List<FixAction> fixes() {
            List<FixAction> out = new ArrayList<FixAction>();
            for (Step s : steps) {
                out.add(s.fix);
            }
            return out;
        }

        public List<ModuleResult> changed() {
            List<ModuleResult> out = new ArrayList<ModuleResult>();
            for (ModuleResult m : modules) {
                if (!m.isUnchanged()) {
                    out.add(m);
                }
            }
            return out;
        }

        /** Gesenkte Versionen oder neue Konflikte – Grund für Exit-Code 3. */
        public boolean hasRisks() {
            for (ModuleResult m : modules) {
                if (!m.risks.isEmpty() || m.count(Kind.DOWNGRADED) > 0) {
                    return true;
                }
            }
            return false;
        }

        public String headline() {
            String from = currentVersions.isEmpty() ? "" : join(new ArrayList<String>(currentVersions), "/") + " → ";
            String what = target == Target.BOM ? "BOM " : target == Target.PARENT ? "Parent " : "";
            return Texts.t("upgrade.headline", what + request.label() + " " + from + request.version);
        }

        /** Konsolenausgabe; ohne {@code all} Module ohne Änderung nur als Liste. */
        public List<String> lines(boolean all) {
            List<String> out = new ArrayList<String>();
            out.add(headline() + " (" + Texts.t("upgrade.poms.unchanged") + ")");
            out.add("");
            if (steps.isEmpty()) {
                out.add(Texts.t("upgrade.no.change.possible"));
            } else {
                out.add(Texts.t("upgrade.steps", steps.size()));
                int i = 1;
                for (Step s : steps) {
                    out.add("  " + i++ + ". " + s.description + "  → " + s.file + (s.line > 0 ? ":" + s.line : ""));
                    if (s.reason != null) {
                        out.add("     " + s.reason);
                    }
                }
            }
            for (String p : problems) {
                out.add("! " + p);
            }
            List<String> unchanged = new ArrayList<String>();
            for (ModuleResult m : modules) {
                if (m.isUnchanged() && !all && m.note == null && m.deviation == null && !m.isIncomplete()) {
                    unchanged.add(m.getName());
                    continue;
                }
                out.add("");
                out.add(Texts.t("upgrade.module", m.getName()) + ": " + moduleSummary(m));
                if (m.note != null) {
                    out.add("  ! " + m.note);
                }
                for (Delta d : m.deltas) {
                    out.add("  " + symbol(d.kind) + " " + d.message());
                }
                for (Risk r : m.risks) {
                    out.add("  ! " + Texts.t("upgrade.conflict") + " " + r.message());
                }
                if (m.deviation != null) {
                    out.add("  (" + m.deviation + ")");
                }
            }
            if (!unchanged.isEmpty()) {
                out.add("");
                out.add(Texts.t("upgrade.unchanged.modules") + " " + join(unchanged, ", "));
            }
            if (!missing.isEmpty()) {
                out.add("");
                out.add(Texts.t("upgrade.incomplete", missing.size()) + " "
                        + join(first(new ArrayList<String>(missing), 8), ", ") + (missing.size() > 8 ? ", …" : ""));
                out.add("  " + Texts.t("upgrade.incomplete.hint"));
            }
            return out;
        }

        /** Markdown für Ticket oder Review. */
        public String markdown(boolean all) {
            StringBuilder sb = new StringBuilder("### ").append(headline()).append("\n\n");
            if (!steps.isEmpty()) {
                sb.append("**").append(Texts.t("upgrade.md.steps")).append("** (").append(Texts.t("upgrade.md.not.applied")).append(")\n\n");
                for (Step s : steps) {
                    sb.append("1. ").append(s.description).append(" — `").append(s.file).append(s.line > 0 ? ":" + s.line : "").append("`\n");
                }
                sb.append('\n');
            }
            for (String p : problems) {
                sb.append("> ⚠ ").append(p).append("\n\n");
            }
            for (ModuleResult m : modules) {
                if (m.isUnchanged() && !all && m.note == null && !m.isIncomplete()) {
                    continue;
                }
                sb.append("#### ").append(Texts.t("upgrade.module", "`" + m.getName() + "`")).append(" – ").append(moduleSummary(m)).append("\n\n");
                if (m.note != null) {
                    sb.append("> ").append(m.note).append("\n\n");
                }
                if (!m.deltas.isEmpty()) {
                    sb.append(Texts.t("upgrade.md.table")).append("\n|---|---|---|---|---|\n");
                    for (Delta d : m.deltas) {
                        DepNode via = d.after != null ? d.after : d.before;
                        sb.append("| ").append(symbol(d.kind)).append(" | `").append(d.label()).append("` | ")
                                .append(d.before == null ? "–" : d.before.getVersion() + scopeSuffix(d.before)).append(" | ")
                                .append(d.after == null ? "–" : d.after.getVersion() + scopeSuffix(d.after)).append(" | ")
                                .append(via.depth() <= 1 ? Texts.t("upgrade.direct") : via.pathText().replace("|", "\\|")).append(" |\n");
                    }
                    sb.append('\n');
                }
                for (Risk r : m.risks) {
                    sb.append("- ⚠ ").append(r.message()).append('\n');
                }
                if (!m.risks.isEmpty()) {
                    sb.append('\n');
                }
            }
            if (changed().isEmpty()) {
                sb.append(Texts.t("upgrade.no.module.changes")).append("\n");
            }
            if (!missing.isEmpty()) {
                sb.append("\n**").append(Texts.t("upgrade.md.incomplete")).append("** ").append(join(first(new ArrayList<String>(missing), 12), ", "))
                        .append(missing.size() > 12 ? ", …" : "").append('\n');
            }
            return sb.toString();
        }

        private static String scopeSuffix(DepNode n) {
            return n.getScope() == null || "compile".equals(n.getScope()) ? "" : " (" + n.getScope() + ")";
        }

        private String moduleSummary(ModuleResult m) {
            String summary = changeSummary(m);
            return m.isIncomplete() ? summary + " – " + Texts.t("upgrade.summary.incomplete", m.missing.size()) : summary;
        }

        private String changeSummary(ModuleResult m) {
            if (m.isUnchanged()) {
                return m.targetBefore != null && m.targetBefore.equals(m.targetAfter) ? Texts.t("upgrade.summary.stays", m.targetAfter)
                        : Texts.t("upgrade.summary.none");
            }
            List<String> parts = new ArrayList<String>();
            add(parts, m.count(Kind.UPGRADED), Texts.t("upgrade.summary.upgraded"));
            add(parts, m.count(Kind.DOWNGRADED), Texts.t("upgrade.summary.downgraded"));
            add(parts, m.count(Kind.ADDED), Texts.t("upgrade.summary.added"));
            add(parts, m.count(Kind.REMOVED), Texts.t("upgrade.summary.removed"));
            add(parts, m.count(Kind.SCOPE), Texts.t("upgrade.summary.scope"));
            add(parts, m.risks.size(), Texts.t("upgrade.summary.risks", m.risks.size()));
            return join(parts, ", ");
        }

        private static void add(List<String> parts, int n, String what) {
            if (n > 0) {
                parts.add(n + " " + what);
            }
        }
    }

    private UpgradeSimulator() {
    }

    /**
     * Simuliert das Upgrade über alle Module.
     *
     * @param locator   Locator für die POMs von heute
     * @param poms      POMs des Projekts (Reactor)
     * @param realTrees echte Bäume je {@code groupId:artifactId} (IDE oder {@code dependency:tree}); nur für den
     *                  Hinweis, wo die Simulation schon heute abweicht – darf leer sein
     */
    public static Result simulate(PomLocator locator, List<PomFile> poms, Request request, Map<String, DepNode> realTrees,
                                  Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        Result result = new Result(request);
        Set<Path> projectFiles = new HashSet<Path>();
        for (PomFile p : poms) {
            projectFiles.add(p.getFile());
        }
        Map<Path, DependencyResolver.Tree> before = new LinkedHashMap<Path, DependencyResolver.Tree>();
        // je Modul die POMs, die seine Auflösung gelesen hat: nur wer eine geänderte sieht, wird neu aufgelöst
        Map<Path, Set<Path>> touched = new HashMap<Path, Set<Path>>();
        for (PomFile p : poms) {
            cancel.check();
            Set<Path> t = new HashSet<Path>();
            locator.recordTouched(t);
            try {
                before.put(p.getFile(), DependencyResolver.resolve(locator, p, cancel));
            } finally {
                locator.recordTouched(null);
            }
            touched.put(p.getFile(), t);
        }

        Map<String, Step> steps = new LinkedHashMap<String, Step>();
        new Planner(locator, poms, projectFiles, request, before, result, steps).plan();

        // Änderungen im Speicher anwenden – je Datei der Reihe nach, wie später beim Übernehmen
        Map<Path, String> changed = new LinkedHashMap<Path, String>();
        for (Step s : steps.values()) {
            Path file = java.nio.file.Paths.get(s.file).toAbsolutePath().normalize();
            String xml = changed.get(file);
            try {
                if (xml == null) {
                    xml = TextFile.read(file).getText();
                }
                PomEditor.Edit edit = PomEditor.forFix(xml, s.fix);
                String next = edit.apply(xml);
                if (next.equals(xml)) {
                    continue;   // steht schon so in der POM
                }
                s.description = edit.description;
                s.line = PomEditor.lineOf(xml, edit.offset);
                changed.put(file, next);
                result.steps.add(s);
            } catch (IOException e) {
                result.problems.add(Texts.t("upgrade.unreadable", s.file, e.getMessage()));
            } catch (IllegalArgumentException e) {
                result.problems.add(Texts.t("upgrade.step.failed", s.fix.getLabel(), e.getMessage()));
            }
        }
        if (result.steps.isEmpty() && !steps.isEmpty() && result.problems.isEmpty()) {
            result.problems.add(Texts.t("upgrade.up.to.date", request.label(), request.version));
        }
        PomLocator after;
        try {
            after = locator.withOverrides(changed);
        } catch (IllegalArgumentException e) {
            result.problems.add(e.getMessage());
            return result;
        }

        for (PomFile p : poms) {
            cancel.check();
            DependencyResolver.Tree b = before.get(p.getFile());
            PomFile changedPom = after.load(p.getFile());
            if (changedPom == null) {
                continue;
            }
            Set<Path> seen = touched.get(p.getFile());
            boolean affected = seen == null || !java.util.Collections.disjoint(seen, changed.keySet());
            // unberührte Module: derselbe Baum, keine zweite Auflösung und kein zweiter Baum im Speicher
            DependencyResolver.Tree a = affected ? DependencyResolver.resolve(after, changedPom, cancel) : b;
            ModuleResult m = new ModuleResult(p, b.getRoot(), a.getRoot());
            for (String miss : a.getMissing()) {
                if (!b.getMissing().contains(miss)) {
                    m.missing.add(miss);
                    result.missing.add(miss);
                }
            }
            diff(m);
            if (!request.isProperty()) {
                DepNode tb = b.getRoot().findIncluded(request.groupId, request.artifactId);
                DepNode ta = a.getRoot().findIncluded(request.groupId, request.artifactId);
                m.targetBefore = tb == null ? null : tb.getVersion();
                m.targetAfter = ta == null ? null : ta.getVersion();
                if (m.targetBefore != null) {
                    result.currentVersions.add(m.targetBefore);
                }
                if (result.target == Target.ARTIFACT && ta != null && !request.version.equals(ta.getVersion())) {
                    VersionExplanation x = new VersionExplainer(after, null).explain(changedPom, request.groupId, request.artifactId, a.getRoot());
                    m.note = Texts.t("upgrade.note.stays", request.ga(), ta.getVersion(), x.getSummary());
                }
            }
            DepNode real = realTrees == null ? null : realTrees.get(p.getGroupId() + ":" + p.getArtifactId());
            if (real != null) {
                m.deviation = deviation(real, b.getRoot());
            }
            result.modules.add(m);
        }
        if (result.target != Target.ARTIFACT || result.currentVersions.isEmpty()) {
            for (Step s : result.steps) {
                String c = s.fix.param("current");
                if (c != null) {
                    result.currentVersions.add(c);
                }
            }
        }
        return result;
    }

    /**
     * Vergleicht die Classpath-Knoten (INCLUDED) beider Bäume.
     *
     * <p>Fehlen dem neuen Baum POMs ({@link ModuleResult#isIncomplete()}), fehlen ihm auch deren Management und
     * Kinder: Versionen werden unbekannt ({@code null}), Kinder verschwinden, ohne BOM gewinnt eine ältere
     * transitive Version. Solche Lücken sind keine Downgrades – deshalb meldet ein unvollständiges Modul nur, was
     * steigt oder dazukommt; gesenkte, entfernte und neue Konflikte erst, wenn die POMs da sind.
     */
    private static void diff(ModuleResult m) {
        boolean incomplete = m.isIncomplete();
        Map<String, DepNode> b = included(m.before);
        Map<String, DepNode> a = included(m.after);
        for (Map.Entry<String, DepNode> e : a.entrySet()) {
            DepNode old = b.get(e.getKey());
            DepNode now = e.getValue();
            if (old == null) {
                if (now.getVersion() != null) {
                    m.deltas.add(new Delta(Kind.ADDED, null, now));
                }
            } else if (!same(old.getVersion(), now.getVersion())) {
                if (old.getVersion() == null || now.getVersion() == null) {
                    continue;   // Version unbekannt (POM fehlt) – steht unter „fehlt“, ist kein Downgrade
                }
                boolean down = MavenVersion.compare(now.getVersion(), old.getVersion()) < 0;
                if (!down || !incomplete) {
                    m.deltas.add(new Delta(down ? Kind.DOWNGRADED : Kind.UPGRADED, old, now));
                }
            } else if (!same(old.getScope(), now.getScope())) {
                m.deltas.add(new Delta(Kind.SCOPE, old, now));
            }
        }
        if (incomplete) {
            return;
        }
        for (Map.Entry<String, DepNode> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                m.deltas.add(new Delta(Kind.REMOVED, e.getValue(), null));
            }
        }
        Set<String> oldRisks = new HashSet<String>();
        for (Risk r : risks(m.before)) {
            oldRisks.add(r.key());
        }
        for (Risk r : risks(m.after)) {
            if (!oldRisks.contains(r.key())) {
                m.risks.add(r);
            }
        }
    }

    /** Knoten im Classpath in Breitensuche, je Schlüssel der erste. */
    private static Map<String, DepNode> included(DepNode root) {
        Map<String, DepNode> out = new LinkedHashMap<String, DepNode>();
        List<DepNode> level = new ArrayList<DepNode>(root.getChildren());
        for (int guard = 0; !level.isEmpty() && guard < 100; guard++) {
            List<DepNode> next = new ArrayList<DepNode>();
            for (DepNode n : level) {
                if (n.getState() != DepNode.State.INCLUDED) {
                    continue;
                }
                String key = n.ga() + ":" + n.getType() + ":" + (n.getClassifier() == null ? "" : n.getClassifier());
                if (!out.containsKey(key)) {
                    out.put(key, n);
                }
                next.addAll(n.getChildren());
            }
            level = next;
        }
        return out;
    }

    /**
     * Verdrängte Knoten, die eine höhere Version wollten als die, die gewonnen hat – im heutigen Baum
     * eines Moduls die Kandidaten für {@code NoSuchMethodError} (Gutter-Icon in der IDE).
     */
    public static List<Risk> risks(DepNode root) {
        List<Risk> out = new ArrayList<Risk>();
        List<DepNode> level = new ArrayList<DepNode>(root.getChildren());
        for (int guard = 0; !level.isEmpty() && guard < 100; guard++) {
            List<DepNode> next = new ArrayList<DepNode>();
            for (DepNode n : level) {
                if (n.getState() == DepNode.State.CONFLICT && n.getRelatedVersion() != null && n.getVersion() != null
                        && MavenVersion.compare(n.getVersion(), n.getRelatedVersion()) > 0) {
                    out.add(new Risk(n));
                }
                next.addAll(n.getChildren());
            }
            level = next;
        }
        return out;
    }

    /** Wie weit weicht der simulierte heutige Baum vom echten ab? {@code null} = gar nicht. */
    private static String deviation(DepNode real, DepNode simulated) {
        Map<String, DepNode> r = included(real);
        Map<String, DepNode> s = included(simulated);
        List<String> examples = new ArrayList<String>();
        int n = 0;
        Set<String> keys = new LinkedHashSet<String>(r.keySet());
        keys.addAll(s.keySet());
        for (String k : keys) {
            DepNode x = r.get(k);
            DepNode y = s.get(k);
            if (x != null && y != null && same(x.getVersion(), y.getVersion())) {
                continue;
            }
            n++;
            if (examples.size() < 3) {
                DepNode any = x != null ? x : y;
                examples.add(Texts.t("upgrade.deviation.example", any.ga(), x == null ? "–" : x.getVersion(), y == null ? "–" : y.getVersion()));
            }
        }
        if (n == 0) {
            return null;
        }
        return Texts.t("upgrade.deviation", n, join(examples, "; "));
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Sucht für jedes betroffene Modul die Stelle, die die Version heute festlegt. */
    private static final class Planner {
        private final PomLocator locator;
        private final List<PomFile> poms;
        private final Set<Path> projectFiles;
        private final Request request;
        private final Map<Path, DependencyResolver.Tree> before;
        private final Result result;
        private final Map<String, Step> steps;
        private final Set<String> problems = new LinkedHashSet<String>();
        /** Mindestens ein Modul hat die Zielversion schon. */
        private boolean upToDate;

        Planner(PomLocator locator, List<PomFile> poms, Set<Path> projectFiles, Request request,
                Map<Path, DependencyResolver.Tree> before, Result result, Map<String, Step> steps) {
            this.locator = locator;
            this.poms = poms;
            this.projectFiles = projectFiles;
            this.request = request;
            this.before = before;
            this.result = result;
            this.steps = steps;
        }

        void plan() {
            if (request.isProperty()) {
                result.target = Target.PROPERTY;
                planProperty();
            } else if (planParent()) {
                result.target = Target.PARENT;
            } else if (planBom()) {
                result.target = Target.BOM;
            } else {
                planArtifact();
            }
            result.problems.addAll(problems);
            if (steps.isEmpty() && problems.isEmpty()) {
                result.problems.add(upToDate ? Texts.t("upgrade.up.to.date", request.label(), request.version)
                        : Texts.t("upgrade.not.found", request.label()));
            }
        }

        private boolean isProject(PomFile p) {
            return p != null && projectFiles.contains(p.getFile());
        }

        /** Oberste POM der Kette, die noch zum Projekt gehört (ab dem Modul ohne Lücke). */
        private PomFile topmost(PomFile module) {
            PomFile top = module;
            for (PomFile p : locator.model(module).getChain()) {
                if (!isProject(p)) {
                    break;
                }
                top = p;
            }
            return top;
        }

        private void add(FixAction fix, String reason) {
            Step s = new Step(fix, reason);
            if (!steps.containsKey(s.key())) {
                steps.put(s.key(), s);
            }
        }

        private void setProperty(PomFile file, String name, String current, String reason) {
            add(FixAction.of(FixAction.SET_VERSION, Texts.t("upgrade.fix.property", "${" + name + "}", file.getArtifactId(), request.version),
                    "file", file.getFile().toString(), "property", name, "version", request.version, "current", current), reason);
        }

        private void setVersion(PomFile file, String section, String ga, String current, String reason) {
            add(FixAction.of(FixAction.SET_VERSION, Texts.t("upgrade.fix.version", ga, file.getArtifactId(), request.version),
                    "file", file.getFile().toString(), "artifact", ga, "section", section, "version", request.version, "current", current),
                    reason);
        }

        private void pin(PomFile module, String current, String reason) {
            PomFile top = topmost(module);
            add(FixAction.of(FixAction.PIN_VERSION, Texts.t("upgrade.fix.pin", request.ga() + ":" + request.version, top.getArtifactId()),
                    "file", top.getFile().toString(), "artifact", request.ga(), "version", request.version, "current", current), reason);
        }

        /**
         * Version, die als {@code ${p}} angegeben ist: im Projekt definiert → dort ändern; im Parent
         * außerhalb → im Projekt überschreiben; eingebaut ({@code project.version}) → {@code false}.
         */
        private boolean viaProperty(PomFile module, EffectiveModel context, String raw, String current, String what) {
            Matcher m = SINGLE_PROPERTY.matcher(raw == null ? "" : raw.trim());
            if (!m.matches()) {
                return false;
            }
            String name = m.group(1);
            PropertyOrigin o = context.property(name);
            if (o == null || o.pom == null) {
                return false;
            }
            if (isProject(o.pom)) {
                setProperty(o.pom, name, o.value, Texts.t("upgrade.reason.property", what, "${" + name + "}", o.pom.getArtifactId()));
            } else {
                setProperty(topmost(module), name, o.value, Texts.t("upgrade.reason.property.outside", what, "${" + name + "}", o.pom.getArtifactId()));
            }
            return true;
        }

        private void planProperty() {
            boolean defined = false;
            for (PomFile p : poms) {
                if (p.getProperties().containsKey(request.property)) {
                    defined = true;
                    setProperty(p, request.property, p.getProperties().get(request.property).getValue(),
                            Texts.t("upgrade.reason.property.defined", p.getArtifactId()));
                }
            }
            if (defined) {
                return;
            }
            Set<Path> tops = new LinkedHashSet<Path>();
            for (PomFile p : poms) {
                PomFile top = topmost(p);
                PropertyOrigin o = locator.model(p).property(request.property);
                if (o != null && tops.add(top.getFile())) {
                    setProperty(top, request.property, o.value, Texts.t("upgrade.reason.property.override", o.pom == null ? "Maven" : o.pom.getArtifactId()));
                }
            }
            if (tops.isEmpty()) {
                problems.add(Texts.t("upgrade.property.undefined", "${" + request.property + "}"));
            }
        }

        private boolean planParent() {
            boolean found = false;
            for (PomFile p : poms) {
                if (!request.groupId.equals(p.getParentGroupId()) || !request.artifactId.equals(p.getParentArtifactId())) {
                    continue;
                }
                found = true;
                String v = p.getParentVersion();
                if (v != null && v.contains("${")) {
                    problems.add(Texts.t("upgrade.parent.expression", p.getFile(), v));
                    continue;
                }
                setVersion(p, "parent", request.ga(), v, Texts.t("upgrade.reason.parent", p.getArtifactId()));
            }
            return found;
        }

        private boolean planBom() {
            boolean found = false;
            for (PomFile module : poms) {
                EffectiveModel model = locator.model(module);
                for (ImportStep s : model.getImports()) {
                    if (!request.ga().equals(s.declaration.ga())) {
                        continue;
                    }
                    found = true;
                    String what = Texts.t("upgrade.reason.bom", s.declaration.getArtifactId(), s.declaredIn.getArtifactId());
                    if (viaProperty(module, model, s.declaration.getVersion(), s.version.value, what)) {
                        continue;
                    }
                    if (isProject(s.declaredIn) && s.declaration.getVersion() != null && !s.declaration.getVersion().contains("${")) {
                        setVersion(s.declaredIn, "dependencyManagement", request.ga(), s.version.value, what);
                    } else if (isProject(s.declaredIn)) {
                        problems.add(Texts.t("upgrade.bom.expression", what, s.declaration.getVersion()));
                    } else {
                        problems.add(Texts.t("upgrade.bom.outside", what, s.version.value));
                    }
                }
            }
            return found;
        }

        private void planArtifact() {
            for (PomFile module : poms) {
                EffectiveModel model = locator.model(module);
                DepNode tree = before.get(module.getFile()).getRoot();
                Declared declared = model.declared(request.groupId, request.artifactId);
                Managed managed = model.managed(request.groupId, request.artifactId);
                boolean inTree = !tree.findAll(request.groupId, request.artifactId).isEmpty();
                if (declared == null && managed == null && !inTree) {
                    continue;
                }
                DepNode inc = tree.findIncluded(request.groupId, request.artifactId);
                String current = inc != null ? inc.getVersion() : declared != null || managed != null
                        ? ExclusionAudit.dependencyVersion(model, declared != null ? declared.dep : managed.dep) : null;
                if (request.pin) {
                    pin(module, current, Texts.t("upgrade.reason.pin"));
                    continue;
                }
                if (inc != null && request.version.equals(current)) {
                    upToDate = true;
                    continue;   // hat die Zielversion schon – kein Schritt „von X auf X“
                }
                if (declared != null && declared.dep.getVersion() != null) {
                    planDirect(module, model, declared, current);
                } else if (managed != null && managed.via.isEmpty()) {
                    String what = Texts.t("upgrade.reason.managed", managed.declaredIn.getArtifactId());
                    if (viaProperty(module, model, managed.dep.getVersion(), current, what)) {
                        continue;
                    }
                    if (isProject(managed.declaredIn) && managed.dep.getVersion() != null && !managed.dep.getVersion().contains("${")) {
                        setVersion(managed.declaredIn, "dependencyManagement", request.ga(), current, what + " (dependencyManagement)");
                    } else {
                        pin(module, current, Texts.t("upgrade.reason.managed.outside", what));
                    }
                } else if (managed != null) {
                    planBomManaged(module, model, managed, current);
                } else {
                    pin(module, current, Texts.t("upgrade.reason.transitive"));
                }
            }
        }

        private void planDirect(PomFile module, EffectiveModel model, Declared declared, String current) {
            String raw = declared.dep.getVersion();
            String what = Texts.t("upgrade.reason.direct", declared.declaredIn.getArtifactId());
            if (viaProperty(module, model, raw, current, what)) {
                return;
            }
            if (raw.contains("${")) {
                problems.add(Texts.t("upgrade.direct.expression", request.ga(), declared.declaredIn.getFile(), raw));
            } else if (isProject(declared.declaredIn)) {
                setVersion(declared.declaredIn, "dependencies", request.ga(), current, what);
            } else {
                problems.add(Texts.t("upgrade.direct.outside", request.ga(), declared.declaredIn.gav(), raw));
            }
        }

        /**
         * Aus einem BOM: Hat das BOM dieselbe Versionsnummer (Release-Zug wie bei Jackson oder Netty),
         * wird das BOM gehoben, sonst die Version im Projekt festgelegt.
         */
        private void planBomManaged(PomFile module, EffectiveModel model, Managed managed, String current) {
            ImportStep first = managed.via.get(0);
            String bom = first.declaration.ga();
            if (current != null && current.equals(first.version.value)) {
                String what = Texts.t("upgrade.reason.bom.train", first.declaration.getArtifactId());
                if (viaProperty(module, model, first.declaration.getVersion(), current, what)) {
                    return;
                }
                if (isProject(first.declaredIn) && first.declaration.getVersion() != null && !first.declaration.getVersion().contains("${")) {
                    setVersion(first.declaredIn, "dependencyManagement", bom, current, what);
                    return;
                }
            }
            pin(module, current, Texts.t("upgrade.reason.bom.pin", bom + ":" + first.version.value));
        }
    }

    /**
     * Versionen von {@code groupId:artifactId}, deren POM im lokalen Repository liegt – absteigend.
     * Für die Auswahl in der IDE; was nicht lokal liegt, kann {@link PomFetcher} holen.
     */
    public static List<String> localVersions(Path repository, String groupId, String artifactId) {
        List<String> out = new ArrayList<String>();
        if (repository == null || groupId == null || artifactId == null) {
            return out;
        }
        Path dir = repository;
        for (String s : groupId.split("\\.")) {
            dir = dir.resolve(s);
        }
        dir = dir.resolve(artifactId);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        java.nio.file.DirectoryStream<Path> ds = null;
        try {
            ds = Files.newDirectoryStream(dir);
            for (Path v : ds) {
                String name = String.valueOf(v.getFileName());
                if (Files.isRegularFile(v.resolve(artifactId + "-" + name + ".pom"))) {
                    out.add(name);
                }
            }
        } catch (IOException e) {
            return out;
        } finally {
            if (ds != null) {
                try {
                    ds.close();
                } catch (IOException ignored) {
                    // egal
                }
            }
        }
        Collections.sort(out, Collections.reverseOrder(MavenVersion.ORDER));
        return out;
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static List<String> first(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    private static String symbol(Kind k) {
        switch (k) {
            case ADDED:
                return "+";
            case REMOVED:
                return "−";
            case UPGRADED:
                return "↑";
            case DOWNGRADED:
                return "↓";
            default:
                return "~";
        }
    }
}
