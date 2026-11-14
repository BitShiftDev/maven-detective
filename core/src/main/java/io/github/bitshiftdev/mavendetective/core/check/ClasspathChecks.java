package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.ClassFile;
import io.github.bitshiftdev.mavendetective.core.deps.ClassOrigin;
import io.github.bitshiftdev.mavendetective.core.deps.ClassOrigin.Hit;
import io.github.bitshiftdev.mavendetective.core.deps.ClassOrigin.Target;
import io.github.bitshiftdev.mavendetective.core.deps.ClassOrigin.Where;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel;
import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit;
import io.github.bitshiftdev.mavendetective.core.deps.JarIndex;
import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prüfungen, die Modul A fragen: Wo liegt die Klasse aus der Fehlermeldung im Classpath des
 * gescheiterten Moduls – mehrfach, in einer verdrängten Version, hinter einer Exclusion, im falschen
 * Scope? Lesen nur lokale POMs und JARs, nie das Netz.
 */
final class ClasspathChecks {

    /** Gemeinsamer Cache für alle Diagnosen dieser JVM (IDE: über viele Läufe). */
    static final JarIndex JARS = new JarIndex();

    private static final int MAX_REPOSITORY_JARS = 600;

    private static final Pattern ON_PROJECT = Pattern.compile("on project ([\\w.\\-]+)");
    private static final Pattern SOURCE = Pattern.compile("(?<![^\\s:\\[(])((?:[A-Za-z]:)?[^\\s:\\[]+\\.(?:java|kt|groovy)):\\[\\d+,\\d+\\]");
    private static final Pattern NO_SUCH_METHOD = Pattern.compile("NoSuchMethodError: (.+)$");
    private static final Pattern NO_SUCH_FIELD = Pattern.compile("NoSuchFieldError: (.+)$");
    private static final Pattern ABSTRACT = Pattern.compile("AbstractMethodError: .*of interface ([\\w.$]+)");
    private static final Pattern INCOMPATIBLE = Pattern.compile("IncompatibleClassChangeError: (?:Found (?:interface|class) |class )([\\w.$]+)");
    private static final Pattern NO_CLASS_DEF = Pattern.compile("NoClassDefFoundError: ([\\w/.$]+)");
    private static final Pattern NOT_FOUND = Pattern.compile("ClassNotFoundException: ([\\w.$]+)");
    private static final Pattern COULD_NOT_INIT = Pattern.compile("NoClassDefFoundError: Could not initialize class ([\\w.$]+)");
    private static final Pattern FRAME = Pattern.compile("^\\s*at ([\\w$]+(?:\\.[\\w$]+)+)\\.[\\w$<>]+\\(");

    private ClasspathChecks() {
    }

    // ------------------------------------------------------------------ class-origin (Regel 10)

    /** Laufzeitkonflikt: Wer liefert die Klasse, welche Version gewann, wer hätte das Mitglied? */
    static final class ClassOriginCheck implements Check {
        @Override
        public String id() { return "class-origin"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Matcher init = ctx.find(COULD_NOT_INIT);
            if (init != null) {
                return CheckResult.info().cause(Texts.t("check.origin.init.cause", init.group(1)))
                        .evidence(Evidence.hint(Texts.t("check.origin.init.hint")))
                        .fix(FixAction.of(FixAction.MANUAL, Texts.t("check.origin.init.fix", init.group(1))));
            }
            Target target = runtimeTarget(ctx);
            if (target == null) {
                return CheckResult.notApplicable();
            }
            Project project = Project.of(ctx);
            PomFile module = project == null ? null : project.module(ctx);
            if (module == null) {
                return CheckResult.notApplicable();
            }
            ClassOrigin o = project.origin(module, target);
            String className = target.getName();
            String moduleName = module.getArtifactId();
            List<Hit> onCp = o.hits(Where.CLASSPATH);
            CheckResult r;
            if (onCp.size() >= 2) {
                r = duplicates(o, onCp, className, moduleName);
            } else if (onCp.size() == 1) {
                r = single(ctx, project, module, o, onCp.get(0), className, moduleName);
            } else {
                r = absent(project, module, o, className, moduleName, false);
            }
            caller(ctx, project, o, r);
            return r.var("originModule", moduleName);
        }
    }

    /** Mehrere Fassungen im Classpath: die erste lädt die JVM. */
    private static CheckResult duplicates(ClassOrigin o, List<Hit> onCp, String className, String moduleName) {
        Hit winner = onCp.get(0);
        ClassFile.Member member = o.getTarget().getMember();
        Hit better = null;
        if (member != null && Boolean.FALSE.equals(winner.getHasMember())) {
            for (Hit h : onCp) {
                if (Boolean.TRUE.equals(h.getHasMember())) {
                    better = h;
                    break;
                }
            }
        }
        boolean differing = false;
        for (Hit h : onCp) {
            differing |= h.getCrc() != winner.getCrc();
        }
        CheckResult r;
        if (better != null) {
            r = CheckResult.confirmed().cause(Texts.t("check.origin.dup.member.cause", className, moduleName, winner.label(),
                    member.label(), better.label()));
        } else if (differing) {
            r = CheckResult.confirmed().cause(Texts.t("check.origin.dup.cause", className, moduleName, onCp.size(), winner.label(),
                    others(onCp, winner)));
        } else {
            r = CheckResult.info().evidence(Evidence.hint(Texts.t("check.origin.dup.identical", className, onCp.size())));
        }
        jarEvidence(r, onCp, member);
        DuplicateClasses.Clash clash = clash(o, winner);
        if (clash != null) {
            if (better != null) {
                for (FixAction f : clash.fixesFor(winner.getEntry())) {
                    r.fix(f);
                }
            } else {
                if (clash.getDrop() != null) {
                    r.evidence(Evidence.hint(clash.recommendation()));
                }
                for (FixAction f : clash.fixes()) {
                    r.fix(f);
                }
                if (clash.getDrop() == null && differing) {
                    for (Hit h : onCp) {
                        for (FixAction f : clash.fixesFor(h.getEntry())) {
                            r.fix(f);
                        }
                    }
                }
            }
        }
        return r;
    }

    /** Genau eine Fassung im Classpath: kennt sie das Mitglied, und wurde eine andere verdrängt? */
    private static CheckResult single(CheckContext ctx, Project project, PomFile module, ClassOrigin o, Hit winner, String className,
                                      String moduleName) {
        ClassFile.Member member = o.getTarget().getMember();
        List<Hit> displaced = new ArrayList<Hit>();
        for (Hit h : o.hits(Where.DISPLACED)) {
            if (h.ga().equals(winner.ga())) {
                displaced.add(h);
            }
        }
        CheckResult r;
        if (member != null && Boolean.FALSE.equals(winner.getHasMember())) {
            Hit better = best(displaced, true);
            if (better != null) {
                r = CheckResult.confirmed().cause(displacedCause(better, className, member.label(), winner));
                for (FixAction f : project.raise(module, better)) {
                    r.fix(f);
                }
            } else {
                r = CheckResult.info().cause(Texts.t("check.origin.lacks.cause", className, winner.label(), moduleName, member.label(),
                        winner.ga()));
            }
        } else if (member != null && Boolean.TRUE.equals(winner.getHasMember())) {
            r = CheckResult.info().evidence(Evidence.hint(Texts.t("check.origin.has.member", className, winner.label(), member.label())));
        } else {
            r = CheckResult.info();
        }
        jarEvidence(r, Collections.singletonList(winner), member);
        for (Hit d : displaced) {
            r.evidence(Evidence.file(displacedEvidence(d, member), d.getFile().toString(), -1));
        }
        if (!winner.isSelf()) {
            r.fix(FixAction.of(FixAction.SHOW_DEPENDENCY, Texts.t("check.origin.show", winner.ga()), "artifact", winner.ga()));
        }
        return r;
    }

    /** Nicht im Classpath: ausgeschlossen, verdrängt oder fehlend. */
    private static CheckResult absent(Project project, PomFile module, ClassOrigin o, String className, String moduleName, boolean test) {
        List<Hit> excluded = o.hits(Where.EXCLUDED);
        if (!excluded.isEmpty()) {
            Hit h = excluded.get(0);
            ExclusionAudit.Finding f = h.getExclusion();
            String dep = f.getDependency().ga();
            return CheckResult.confirmed()
                    .cause(Texts.t("check.origin.excluded.cause", className, h.label(), f.getExclusion().ga(), dep, moduleName))
                    .evidence(Evidence.file(Texts.t("check.origin.excluded.evidence", f.getExclusion().ga(), dep), f.getFile(), f.getLine()))
                    .evidence(Evidence.file(Texts.t("check.origin.jar.has", h.label()), h.getFile().toString(), -1))
                    .fix(FixAction.of(FixAction.REMOVE_EXCLUSION, Texts.t("check.origin.excluded.fix", f.getExclusion().ga(), dep),
                            "file", f.getFile(), "artifact", dep, "exclusion", f.getExclusion().ga(),
                            "section", f.isManaged() ? "dependencyManagement" : "dependencies"));
        }
        Hit d = best(o.hits(Where.DISPLACED), false);
        if (d != null) {
            boolean higher = MavenVersion.compare(d.getVersion(), d.getChosenVersion()) > 0;
            CheckResult r = CheckResult.confirmed()
                    .cause(Texts.t("check.origin.only.displaced.cause", className, d.label(), d.getArtifactId() + ":" + d.getChosenVersion(),
                            moduleName))
                    .evidence(Evidence.file(displacedEvidence(d, null), d.getFile().toString(), -1));
            if (higher) {
                for (FixAction f : project.raise(module, d)) {
                    r.fix(f);
                }
            } else {
                r.evidence(Evidence.hint(Texts.t("check.origin.removed.later", className, d.ga(), d.getChosenVersion())));
            }
            return r.fix(FixAction.of(FixAction.SHOW_DEPENDENCY, Texts.t("check.origin.show", d.ga()), "artifact", d.ga()));
        }
        CheckResult r = CheckResult.info().evidence(Evidence.hint(o.getChecked() == 0
                ? Texts.t("check.origin.none.empty", className, moduleName)
                : Texts.t("check.origin.none", className, o.getChecked(), moduleName)));
        if (!o.getWithoutFile().isEmpty()) {
            r.evidence(Evidence.hint(Texts.t("check.origin.unchecked", o.getWithoutFile().size(), o.getWithoutFile().get(0).gav())));
        }
        List<Hit> local = ClassOrigin.searchRepository(project.locator.getLocalRepository(), o.getTarget(), JARS, MAX_REPOSITORY_JARS,
                Cancellation.NONE);
        if (!local.isEmpty()) {
            Hit h = local.get(0);
            r.cause(Texts.t("check.origin.repository.cause", className, moduleName, h.label()));
            r.evidence(Evidence.file(Texts.t("check.origin.repository", className, h.label()), h.getFile().toString(), -1));
            for (int i = 1; i < local.size() && i < 3; i++) {
                r.evidence(Evidence.file(Texts.t("check.origin.repository.also", local.get(i).label()), local.get(i).getFile().toString(), -1));
            }
            r.fix(project.declare(module, h, test ? "test" : null));
        }
        return r;
    }

    /** Aufrufer aus dem Stacktrace: aus welchem JAR stammt der Code, der das Mitglied erwartet? */
    private static void caller(CheckContext ctx, Project project, ClassOrigin o, CheckResult r) {
        if (o.getTarget().getMember() == null) {
            return;
        }
        String owner = o.getTarget().getMember().getOwner();
        for (String line : ctx.lines()) {
            Matcher m = FRAME.matcher(line);
            if (!m.find()) {
                continue;
            }
            String cls = m.group(1);
            if (cls.equals(owner) || cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")) {
                continue;
            }
            for (ModuleClasspath.Entry e : o.getClasspath().getEntries()) {
                if (e.getFile() == null) {
                    continue;
                }
                try {
                    String top = cls.indexOf('$') > 0 ? cls.substring(0, cls.indexOf('$')) : cls;
                    if (JARS.read(e.getFile()).contains(top)) {
                        r.evidence(Evidence.file(Texts.t("check.origin.caller", cls, e.label()), e.getFile().toString(), -1));
                        return;
                    }
                } catch (java.io.IOException ex) {
                    // nicht lesbar
                }
            }
            return; // nur der erste fremde Rahmen zählt
        }
    }

    // ------------------------------------------------------------------ class-location (Regel 9)

    /** Compiler-Fehler: Liegt das Paket in einem JAR mit falschem Scope, hinter einer Exclusion oder nur lokal? */
    static final class ClassLocationCheck implements Check {
        @Override
        public String id() { return "class-location"; }

        @Override
        public CheckResult run(CheckContext ctx) {
            Target target = ctx.var("package") != null ? Target.pkg(ctx.var("package"))
                    : ctx.var("class") != null ? Target.type(ctx.var("class")) : null;
            if (target == null) {
                return CheckResult.notApplicable();
            }
            Project project = Project.of(ctx);
            PomFile module = project == null ? null : project.module(ctx);
            if (module == null) {
                return CheckResult.notApplicable();
            }
            String source = sourceFile(ctx);
            boolean test = source != null && (source.contains("/src/test/") || source.contains("\\src\\test\\"));
            ClassOrigin o = project.origin(module, target);
            String name = target.getName();
            String moduleName = module.getArtifactId();
            List<Hit> visible = new ArrayList<Hit>();
            List<Hit> hidden = new ArrayList<Hit>();
            for (Hit h : o.hits(Where.CLASSPATH)) {
                String scope = h.getEntry().getScope();
                if (test || "compile".equals(scope) || "provided".equals(scope) || "system".equals(scope)) {
                    visible.add(h);
                } else {
                    hidden.add(h);
                }
            }
            if (!visible.isEmpty()) {
                Hit h = visible.get(0);
                CheckResult r = CheckResult.info()
                        .evidence(Evidence.file(Texts.t("check.location.visible", name, h.label(), h.getEntry().getScope()),
                                String.valueOf(h.getFile()), -1));
                if (h.getEntry().isReactor()) {
                    r.evidence(Evidence.hint(Texts.t("check.location.reactor", h.getArtifactId())))
                            .fix(FixAction.of(FixAction.REBUILD, Texts.t("check.reactor.module.am"), "args", "-am"));
                } else {
                    r.evidence(Evidence.hint(Texts.t("check.location.stale")));
                }
                return r;
            }
            if (!hidden.isEmpty()) {
                Hit h = hidden.get(0);
                CheckResult r = CheckResult.confirmed()
                        .cause(Texts.t("check.location.scope.cause", name, h.label(), h.getEntry().getScope(), moduleName))
                        .evidence(Evidence.file(Texts.t("check.origin.jar.has", h.label()), h.getFile().toString(), -1));
                return r.fix(project.makeVisible(module, h));
            }
            return absent(project, module, o, name, moduleName, test);
        }
    }

    // ------------------------------------------------------------------ Bausteine

    /** Ziel aus der Meldung eines Laufzeitfehlers; {@code null}, wenn keine Klasse erkennbar ist. */
    static Target runtimeTarget(CheckContext ctx) {
        for (String line : ctx.lines()) {
            Matcher m = NO_SUCH_METHOD.matcher(line);
            if (m.find()) {
                ClassFile.Member member = ClassFile.method(m.group(1));
                if (member != null) {
                    return Target.member(member);
                }
            }
            m = NO_SUCH_FIELD.matcher(line);
            if (m.find()) {
                ClassFile.Member member = ClassFile.field(m.group(1));
                if (member != null) {
                    return Target.member(member);
                }
            }
        }
        for (Pattern p : new Pattern[] {ABSTRACT, INCOMPATIBLE, NO_CLASS_DEF, NOT_FOUND}) {
            Matcher m = ctx.find(p);
            if (m != null) {
                String name = m.group(1).replace('/', '.');
                if (name.contains(".")) {
                    return Target.type(name);
                }
            }
        }
        return null;
    }

    private static String sourceFile(CheckContext ctx) {
        if (ctx.var("sourceFile") != null) {
            return ctx.var("sourceFile");
        }
        Matcher m = ctx.find(SOURCE);
        return m == null ? null : m.group(1);
    }

    private static Hit best(List<Hit> hits, boolean withMember) {
        Hit best = null;
        for (Hit h : hits) {
            if (withMember && !Boolean.TRUE.equals(h.getHasMember())) {
                continue;
            }
            if (best == null || MavenVersion.compare(h.getVersion(), best.getVersion()) > 0) {
                best = h;
            }
        }
        return best;
    }

    private static String displacedCause(Hit better, String className, String member, Hit winner) {
        String requester = better.getRequester();
        if (better.isManaged()) {
            return Texts.t("check.origin.managed.cause", className, winner.label(), member,
                    requester == null ? better.ga() : requester, better.getVersion(), better.getChosenVersion());
        }
        return Texts.t("check.origin.conflict.cause", className, winner.label(), member,
                requester == null ? better.ga() : requester, better.getVersion(), better.getChosenVersion());
    }

    private static String displacedEvidence(Hit d, ClassFile.Member member) {
        String requester = d.getRequester();
        String text = Texts.t(d.isManaged() ? "check.origin.displaced.managed" : "check.origin.displaced.conflict",
                d.label(), requester == null ? Texts.t("check.origin.the.module") : requester, d.getChosenVersion());
        if (member != null && d.getHasMember() != null) {
            text += " – " + Texts.t(d.getHasMember() ? "check.origin.member.yes" : "check.origin.member.no", member.label());
        }
        return text;
    }

    private static void jarEvidence(CheckResult r, List<Hit> hits, ClassFile.Member member) {
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            String text = Texts.t(i == 0 ? "check.origin.jar.first" : "check.origin.jar.other", h.label(), h.getEntry().getScope());
            if (member != null && h.getHasMember() != null) {
                text += " – " + Texts.t(h.getHasMember() ? "check.origin.member.yes" : "check.origin.member.no", member.label());
            }
            if (h.getEntry().getPath().size() > 1) {
                text += " – " + Texts.t("check.origin.via", h.getEntry().getPath().get(0));
            }
            r.evidence(Evidence.file(text, String.valueOf(h.getFile()), -1));
        }
    }

    private static String others(List<Hit> hits, Hit except) {
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            if (h != except) {
                sb.append(sb.length() == 0 ? "" : ", ").append(h.label());
            }
        }
        return sb.toString();
    }

    private static DuplicateClasses.Clash clash(ClassOrigin o, Hit winner) {
        DuplicateClasses.ModuleResult result = DuplicateClasses.analyze(o.getClasspath(), JARS, Cancellation.NONE);
        for (DuplicateClasses.Clash c : result.getClashes()) {
            if (c.getJars().contains(winner.getEntry()) && c.getClasses().contains(winner.getClassName().split("\\$")[0])) {
                return c;
            }
        }
        return null;
    }

    /** Projekt im Speicher: lokale POMs, Reactor und das lokale Repository. */
    static final class Project {
        final PomLocator locator;
        final Path root;
        final List<PomFile> poms = new ArrayList<PomFile>();
        final Map<String, PomFile> reactor = new LinkedHashMap<String, PomFile>();
        final Set<Path> files = new HashSet<Path>();

        private Project(PomLocator locator, Path root) {
            this.locator = locator;
            this.root = root;
        }

        static Project of(CheckContext ctx) {
            Path root = ctx.projectPoms().root();
            Path repo = ctx.env().getLocalRepository();
            if (root == null || repo == null) {
                return null;
            }
            Project p = new Project(new PomLocator(root, repo), root);
            for (ProjectPoms.Pom pom : ctx.projectPoms().all()) {
                if (!pom.parseable) {
                    continue;
                }
                PomFile f = p.locator.load(pom.file);
                if (f != null) {
                    p.poms.add(f);
                    p.reactor.put(f.getGroupId() + ":" + f.getArtifactId(), f);
                    p.files.add(f.getFile());
                }
            }
            return p.poms.isEmpty() ? null : p;
        }

        /** Das gescheiterte Modul: aus der Quelldatei, aus „on project …“ oder als einziges Modul mit Code. */
        PomFile module(CheckContext ctx) {
            String source = sourceFile(ctx);
            if (source != null) {
                PomFile byPath = byPath(source);
                if (byPath != null) {
                    return byPath;
                }
            }
            List<String> names = new ArrayList<String>();
            for (ErrorRecord e : ctx.failure().getErrors()) {
                if (e.getProject() != null) {
                    String[] parts = e.getProject().split(":");
                    names.add(parts.length >= 2 ? parts[1] : parts[0]);
                }
            }
            for (String line : ctx.lines()) {
                Matcher m = ON_PROJECT.matcher(line);
                if (m.find()) {
                    names.add(m.group(1));
                }
            }
            for (String name : names) {
                for (PomFile p : poms) {
                    if (name.equals(p.getArtifactId())) {
                        return p;
                    }
                }
            }
            PomFile only = null;
            for (PomFile p : poms) {
                if (!"pom".equals(p.getPackaging())) {
                    if (only != null) {
                        return null;
                    }
                    only = p;
                }
            }
            return only;
        }

        /** Nächste {@code pom.xml} über der Quelldatei, die zum Projekt gehört. */
        private PomFile byPath(String source) {
            Path p = Text.localPath(source);
            if (p == null) {
                return null;
            }
            if (!p.isAbsolute()) {
                p = root.resolve(p);
            }
            for (Path dir = p.getParent(); dir != null; dir = dir.getParent()) {
                Path pom = dir.resolve("pom.xml").toAbsolutePath().normalize();
                if (files.contains(pom)) {
                    for (PomFile f : poms) {
                        if (f.getFile().equals(pom)) {
                            return f;
                        }
                    }
                }
                if (Files.isRegularFile(pom)) {
                    return null; // fremdes Projekt (z. B. Log von einem anderen Rechner)
                }
            }
            return null;
        }

        ClassOrigin origin(PomFile module, Target target) {
            return ClassOrigin.find(locator, module, reactor, null, files, target, JARS, Cancellation.NONE);
        }

        /**
         * Quick-Fix, der die verdrängte Version {@code d} in den Classpath bringt – über den Hebel, den
         * der Upgrade-Simulator findet (direkte Version, Management, Property, BOM, sonst Pin). Leer,
         * wenn kein einzelner Schritt genügt.
         */
        List<FixAction> raise(PomFile module, Hit d) {
            List<PomFile> chain = new ArrayList<PomFile>();
            for (PomFile p : locator.model(module).getChain()) {
                if (files.contains(p.getFile())) {
                    chain.add(p);
                }
            }
            try {
                UpgradeSimulator.Result result = UpgradeSimulator.simulate(locator, chain,
                        UpgradeSimulator.Request.artifact(d.getGroupId(), d.getArtifactId(), d.getVersion()),
                        new HashMap<String, io.github.bitshiftdev.mavendetective.core.deps.DepNode>(), Cancellation.NONE);
                if (result.getSteps().size() != 1 || !result.getProblems().isEmpty()) {
                    return Collections.emptyList();
                }
                for (UpgradeSimulator.ModuleResult m : result.getModules()) {
                    if (m.getModule().getFile().equals(module.getFile())
                            && (m.getNote() != null || !d.getVersion().equals(m.getTargetAfter()))) {
                        return Collections.emptyList(); // käme im Modul nicht an
                    }
                }
                return result.fixes();
            } catch (RuntimeException e) {
                return Collections.emptyList();
            }
        }

        /** Quick-Fix: Dependency im Modul deklarieren (Version nur, wenn nicht verwaltet). */
        FixAction declare(PomFile module, Hit h, String scope) {
            EffectiveModel model = locator.model(module);
            boolean managed = model.managed(h.getGroupId(), h.getArtifactId()) != null;
            return FixAction.of(FixAction.ADD_DEPENDENCY,
                    Texts.t("check.location.declare", h.ga() + (managed ? "" : ":" + h.getVersion()) + (scope == null ? "" : " (" + scope + ")"),
                            module.getArtifactId()),
                    "file", module.getFile().toString(), "artifact", h.ga(), "version", managed ? null : h.getVersion(), "scope", scope);
        }

        /** Quick-Fix für ein JAR, das nur im Test- oder Laufzeit-Classpath liegt. */
        FixAction makeVisible(PomFile module, Hit h) {
            EffectiveModel model = locator.model(module);
            EffectiveModel.Declared own = model.declared(h.getGroupId(), h.getArtifactId());
            if (own == null) {
                return declare(module, h, null);
            }
            String file = own.declaredIn.getFile().toString();
            if (!own.declaredIn.getFile().equals(module.getFile())) {
                return FixAction.of(FixAction.OPEN_FILE, Texts.t("check.location.inherited", h.ga(), own.declaredIn.getArtifactId()),
                        "file", file, "line", String.valueOf(own.dep.getLine()));
            }
            return FixAction.of(FixAction.SET_SCOPE, Texts.t("check.location.scope.fix", h.ga()),
                    "file", file, "artifact", h.ga(), "scope", "compile", "classifier", own.dep.getClassifier(),
                    "type", "jar".equals(own.dep.getType()) ? null : own.dep.getType());
        }
    }

    static List<Check> all() {
        List<Check> l = new ArrayList<Check>();
        l.add(new ClassOriginCheck());
        l.add(new ClassLocationCheck());
        return l;
    }
}
