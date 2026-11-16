package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.ImportStep;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.PropertyOrigin;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Request;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Result;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Step;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Target;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Planungsteil des {@link UpgradeSimulator}: sucht für jedes betroffene Modul die Stelle, die die
 * Version heute festlegt – Parent, BOM-Import, direkte Angabe, {@code dependencyManagement},
 * Property – und legt dafür einen Schritt ({@code set-version}/{@code pin-version}) an. Ändert
 * nichts; angewendet wird im Speicher vom Simulator.
 */
final class UpgradePlanner {

    private static final Pattern SINGLE_PROPERTY = Pattern.compile("^\\$\\{([^}]+)}$");

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

    UpgradePlanner(PomLocator locator, List<PomFile> poms, Set<Path> projectFiles, Request request,
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
            result.problems.add(upToDate ? Texts.t("upgrade.up.to.date", request.label(), request.getVersion())
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
        add(FixAction.of(FixAction.SET_VERSION, Texts.t("upgrade.fix.property", "${" + name + "}", file.getArtifactId(), request.getVersion()),
                "file", file.getFile().toString(), "property", name, "version", request.getVersion(), "current", current), reason);
    }

    private void setVersion(PomFile file, String section, String ga, String current, String reason) {
        add(FixAction.of(FixAction.SET_VERSION, Texts.t("upgrade.fix.version", ga, file.getArtifactId(), request.getVersion()),
                "file", file.getFile().toString(), "artifact", ga, "section", section, "version", request.getVersion(), "current", current),
                reason);
    }

    private void pin(PomFile module, String current, String reason) {
        PomFile top = topmost(module);
        add(FixAction.of(FixAction.PIN_VERSION, Texts.t("upgrade.fix.pin", request.ga() + ":" + request.getVersion(), top.getArtifactId()),
                "file", top.getFile().toString(), "artifact", request.ga(), "version", request.getVersion(), "current", current), reason);
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
            if (p.getProperties().containsKey(request.getProperty())) {
                defined = true;
                setProperty(p, request.getProperty(), p.getProperties().get(request.getProperty()).getValue(),
                        Texts.t("upgrade.reason.property.defined", p.getArtifactId()));
            }
        }
        if (defined) {
            return;
        }
        Set<Path> tops = new LinkedHashSet<Path>();
        for (PomFile p : poms) {
            PomFile top = topmost(p);
            PropertyOrigin o = locator.model(p).property(request.getProperty());
            if (o != null && tops.add(top.getFile())) {
                setProperty(top, request.getProperty(), o.value, Texts.t("upgrade.reason.property.override", o.pom == null ? "Maven" : o.pom.getArtifactId()));
            }
        }
        if (tops.isEmpty()) {
            problems.add(Texts.t("upgrade.property.undefined", "${" + request.getProperty() + "}"));
        }
    }

    private boolean planParent() {
        boolean found = false;
        for (PomFile p : poms) {
            if (!request.getGroupId().equals(p.getParentGroupId()) || !request.getArtifactId().equals(p.getParentArtifactId())) {
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
            Declared declared = model.declared(request.getGroupId(), request.getArtifactId());
            Managed managed = model.managed(request.getGroupId(), request.getArtifactId());
            boolean inTree = !tree.findAll(request.getGroupId(), request.getArtifactId()).isEmpty();
            if (declared == null && managed == null && !inTree) {
                continue;
            }
            DepNode inc = tree.findIncluded(request.getGroupId(), request.getArtifactId());
            String current = inc != null ? inc.getVersion() : declared != null || managed != null
                    ? ExclusionAudit.dependencyVersion(model, declared != null ? declared.dep : managed.dep) : null;
            if (request.isPin()) {
                pin(module, current, Texts.t("upgrade.reason.pin"));
                continue;
            }
            if (inc != null && request.getVersion().equals(current)) {
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
