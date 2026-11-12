package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.ImportStep;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Interpolated;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.PropertyOrigin;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Source;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Station.Kind;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Modul A1 – „Why this version?“. Erklärt für eine Dependency eines Moduls, woher ihre Version
 * kommt: direkte Angabe, {@code dependencyManagement} (Modul, Parent, importiertes BOM), setzende
 * Property oder – ohne Management – der Pfad im Baum (nearest wins) und was dabei verdrängt wurde.
 *
 * <p>Liest nur POMs aus dem Projekt und dem lokalen Repository, geht nie ins Netz. Der aufgelöste
 * Baum ist optional (IDE-Modell oder {@code mvn dependency:tree -Dverbose}); ohne ihn fehlen
 * Pfad und verdrängte Versionen bei transitiven Dependencies.
 */
public final class VersionExplainer {

    private final PomLocator locator;
    private final Path projectRoot;

    public VersionExplainer(PomLocator locator, Path projectRoot) {
        this.locator = locator;
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    /**
     * @param module      POM des Moduls
     * @param groupId     gesuchte Dependency
     * @param artifactId  gesuchte Dependency
     * @param tree        aufgelöster Baum des Moduls (Wurzel = Modul) oder {@code null}
     */
    public VersionExplanation explain(PomFile module, String groupId, String artifactId, DepNode tree) {
        EffectiveModel model = locator.model(module);
        VersionExplanation x = new VersionExplanation(groupId, artifactId, module.gav());
        DepNode included = tree == null ? null : tree.findIncluded(groupId, artifactId);
        if (included != null) {
            x.setTreeVersion(included.getVersion());
        }
        Declared declared = model.declared(groupId, artifactId);
        Managed managed = model.managed(groupId, artifactId);
        String computed;

        if (declared != null && declared.dep.getVersion() != null) {
            computed = explainDirect(x, model, declared, managed);
        } else if (managed != null) {
            if (declared != null) {
                x.add(Kind.DIRECT, "Deklariert ohne Version in " + label(declared.declaredIn) + " – die Version kommt aus dependencyManagement",
                        file(declared.declaredIn), declared.dep.getLine(), 0);
                inheritance(x, model, declared.declaredIn, "<dependency> geerbt");
            } else if (included != null) {
                x.add(Kind.TREE_PATH, "Transitiv über " + included.pathText(), null, -1, 0);
                if (included.getPremanagedVersion() != null) {
                    x.add(Kind.TREE_PATH, "Ohne dependencyManagement wäre es " + included.getPremanagedVersion()
                            + " (nearest wins) – das Management des Moduls überstimmt transitive Versionen", null, -1, 1);
                }
            } else {
                x.add(Kind.TREE_PATH, "Nicht direkt deklariert; kommt, falls überhaupt, transitiv – dependencyManagement des Moduls bestimmt dann die Version",
                        null, -1, 0);
            }
            computed = explainManaged(x, model, managed, 0);
            x.setSource(managed.via.isEmpty() ? Source.MANAGED : Source.BOM);
            x.setSummary(managedSummary(model, managed, computed));
        } else if (declared != null) {
            computed = null;
            x.add(Kind.DIRECT, "Deklariert ohne Version in " + label(declared.declaredIn) + ", aber kein passender Eintrag in dependencyManagement gefunden",
                    file(declared.declaredIn), declared.dep.getLine(), 0);
            inheritance(x, model, declared.declaredIn, "<dependency> geerbt");
            ImportStep suspect = suspectBom(model, groupId);
            x.setSummary(suspect != null
                    ? "Deklariert ohne Version; die Version kommt vermutlich aus dem BOM " + suspect.declaration.ga() + ":" + suspect.version.value
                    + ", das im lokalen Repository fehlt."
                    : "Deklariert ohne Version, aber kein dependencyManagement liefert eine – so baut Maven nicht ('dependencies.dependency.version' is missing).");
        } else if (included != null) {
            computed = explainTransitive(x, included);
        } else {
            computed = null;
            if (tree != null && !tree.findAll(groupId, artifactId).isEmpty()) {
                x.setSummary(groupId + ":" + artifactId + " steht im Baum, landet aber nicht im Classpath (verdrängt oder ausgeschlossen).");
            } else if (tree != null) {
                x.setSummary(groupId + ":" + artifactId + " kommt in " + module.gav() + " nicht vor – weder deklariert, verwaltet noch transitiv.");
            } else {
                x.setSummary("Weder deklariert noch in dependencyManagement – die Version kommt transitiv (nearest wins).");
                x.add(Kind.HINT, "Für Pfad und verdrängte Versionen den Baum mitgeben: mvn dependency:tree -Dverbose -Dincludes="
                        + groupId + ":" + artifactId + " > tree.txt, dann --tree tree.txt", null, -1, 0);
            }
        }

        if (tree != null) {
            conflicts(x, tree, groupId, artifactId, managed != null && (declared == null || declared.dep.getVersion() == null));
        }
        String version = included != null ? included.getVersion() : computed;
        x.setVersion(version);
        if (included != null && computed != null && !computed.contains("${") && !computed.equals(included.getVersion())) {
            x.add(Kind.HINT, "Der aufgelöste Baum sagt " + included.getVersion() + ", die POMs ergeben " + computed
                    + ". Meist steckt ein aktives Profil, eine -D-Property oder ein veralteter IDE-Import dahinter.", null, -1, 0);
        }
        notes(x, model, managed == null);
        return x;
    }

    private String explainDirect(VersionExplanation x, EffectiveModel model, Declared declared, Managed managed) {
        Interpolated v = model.interpolate(declared.dep.getVersion());
        boolean inherited = declared.declaredIn != model.getPom();
        x.add(Kind.DIRECT, (inherited ? "Geerbte Angabe " : "Direkt angegeben ") + versionTag(declared.dep.getVersion())
                        + " in " + label(declared.declaredIn),
                file(declared.declaredIn), declared.dep.getVersionLine(), 0);
        if (inherited) {
            inheritance(x, model, declared.declaredIn, "<dependency> geerbt");
        }
        properties(x, v, 1);
        x.setSource(Source.DIRECT);
        x.setSummary("Version " + v.value + (inherited ? ": im Parent " + declared.declaredIn.gav() + " direkt angegeben" : ": direkt im Modul angegeben")
                + " – eine direkte Angabe gewinnt immer, auch gegen dependencyManagement.");
        if (managed != null) {
            Interpolated mv = managed.version();
            if (mv.value != null && !mv.value.equals(v.value)) {
                x.add(Kind.HINT, "dependencyManagement in " + label(managed.declaredIn) + " nennt " + mv.value
                                + ", wird hier aber von der direkten Angabe überstimmt. Version weglassen, wenn das Management gelten soll.",
                        file(managed.declaredIn), managed.dep.getVersionLine(), 0);
            }
        }
        return v.value;
    }

    /** Stationen für einen verwalteten Eintrag; liefert die Version. */
    private String explainManaged(VersionExplanation x, EffectiveModel model, Managed managed, int level) {
        if (managed.via.isEmpty()) {
            if (managed.declaredIn != model.getPom()) {
                inheritance(x, model, managed.declaredIn, "dependencyManagement geerbt");
            }
        } else {
            ImportStep first = managed.via.get(0);
            if (first.declaredIn != model.getPom()) {
                inheritance(x, model, first.declaredIn, "BOM-Import geerbt");
            }
            for (ImportStep step : managed.via) {
                x.add(Kind.IMPORT, "Importiert BOM " + step.declaration.ga() + ":" + step.version.value + " "
                                + versionTag(step.declaration.getVersion()) + " in " + label(step.declaredIn),
                        file(step.declaredIn), step.declaration.getVersionLine(), level);
                properties(x, step.version, level + 1);
                if (step.bom != null && step.bom.hasParent() && step.bom != managed.declaredIn
                        && managed.via.indexOf(step) == managed.via.size() - 1) {
                    x.add(Kind.INHERITED, "Eintrag stammt aus dem Parent des BOMs: " + label(managed.declaredIn),
                            file(managed.declaredIn), managed.declaredIn.getParentLine(), level + 1);
                }
            }
        }
        Interpolated v = managed.version();
        x.add(Kind.MANAGED, "dependencyManagement: " + managed.dep.ga() + " " + versionTag(managed.dep.getVersion())
                + " in " + label(managed.declaredIn), file(managed.declaredIn), managed.dep.getVersionLine(), level);
        properties(x, v, level + 1);
        if (!managed.via.isEmpty()) {
            // Nur der erste Import wird im Kontext des Moduls aufgelöst; alles dahinter im Kontext der BOMs
            for (int i = 1; i < managed.via.size(); i++) {
                bomPropertyPitfall(x, model, managed.via.get(i).version);
            }
            bomPropertyPitfall(x, model, v);
        }
        return v.value;
    }

    private String explainTransitive(VersionExplanation x, DepNode included) {
        x.setSource(Source.TRANSITIVE);
        x.add(Kind.TREE_PATH, "Transitiv über " + included.pathText() + " (Tiefe " + included.depth() + ")", null, -1, 0);
        DepNode via = included.getParent();
        String version = included.getVersion();
        if (via != null && via.getParent() != null) {
            PomFile pom = locator.byCoordinates(via.getGroupId(), via.getArtifactId(), via.getVersion());
            if (pom == null) {
                x.add(Kind.HINT, "POM von " + via.gav() + " liegt nicht im lokalen Repository – die Deklaration lässt sich nicht zeigen", null, -1, 0);
            } else {
                EffectiveModel m = locator.model(pom);
                Declared d = m.declared(included.getGroupId(), included.getArtifactId());
                if (d != null) {
                    x.add(Kind.DECLARED_TRANSITIVE, "Deklariert von " + via.ga() + " " + versionTag(d.dep.getVersion())
                            + " in " + label(d.declaredIn), file(d.declaredIn), d.dep.getVersionLine(), 0);
                    if (d.dep.getVersion() != null) {
                        properties(x, m.interpolate(d.dep.getVersion()), 1);
                    } else {
                        Managed inner = m.managed(included.getGroupId(), included.getArtifactId());
                        if (inner != null) {
                            explainManaged(x, m, inner, 1);
                        }
                    }
                }
            }
        }
        x.setSummary("Version " + version + ": transitiv über " + (via == null ? "?" : via.ga())
                + ", kein dependencyManagement im Modul – nearest wins (der kürzeste Pfad gewinnt, bei gleicher Tiefe der zuerst deklarierte).");
        return version;
    }

    private void conflicts(VersionExplanation x, DepNode tree, String groupId, String artifactId, boolean managed) {
        DepNode winner = tree.findIncluded(groupId, artifactId);
        Set<String> seen = new LinkedHashSet<String>();
        for (DepNode n : tree.findAll(groupId, artifactId)) {
            if (n.getState() != DepNode.State.CONFLICT && !(n.getState() == DepNode.State.INCLUDED && n != winner)) {
                continue;
            }
            String requested = n.getPremanagedVersion() != null ? n.getPremanagedVersion() : n.getVersion();
            if (!seen.add(requested + "|" + n.pathText())) {
                continue;
            }
            String why;
            if (managed) {
                why = "dependencyManagement setzt die Version";
            } else if (winner == null) {
                why = "eine andere Version hat gewonnen";
            } else if (n.depth() > winner.depth()) {
                why = "Pfad ist länger (Tiefe " + n.depth() + " gegen " + winner.depth() + ")";
            } else {
                why = "gleich tief, aber der Gewinner steht weiter vorn im POM";
            }
            x.add(Kind.CONFLICT, "Verdrängt: " + requested + " über " + n.pathText() + " – " + why, null, -1, 0);
            if (seen.size() >= 8) {
                break;
            }
        }
    }

    /**
     * Klassiker bei Spring Boot &amp; Co.: Eine Property mit dem Namen aus dem BOM wird im eigenen POM
     * gesetzt, wirkt aber nicht, weil das BOM importiert und nicht geerbt wird.
     */
    private void bomPropertyPitfall(VersionExplanation x, EffectiveModel model, Interpolated v) {
        for (PropertyOrigin used : v.used) {
            PropertyOrigin own = model.property(used.name);
            if (own != null && own.pom != null && own.value != null && !own.value.equals(used.value)
                    && model.getChain().contains(own.pom) && own.pom != used.pom) {
                x.add(Kind.HINT, "Die Property ${" + used.name + "} = " + own.value + " in " + label(own.pom)
                                + " wirkt hier nicht: Das BOM wird importiert, nicht geerbt, und löst seine Properties im eigenen Kontext auf. "
                                + "Abhilfe: den Eintrag vor dem Import selbst in dependencyManagement setzen oder die BOM-Version ändern.",
                        file(own.pom), own.line, 0);
            }
        }
    }

    private void properties(VersionExplanation x, Interpolated v, int level) {
        for (PropertyOrigin o : v.used) {
            if (o.pom == null) {
                x.add(Kind.PROPERTY, "${" + o.name + "} = " + o.value + " (eingebaut)", null, -1, level);
                continue;
            }
            x.add(Kind.PROPERTY, "${" + o.name + "} = " + o.value + " gesetzt in " + label(o.pom), file(o.pom), o.line, level);
            for (PropertyOrigin s : o.shadowed) {
                x.add(Kind.PROPERTY, "überschreibt " + s.value + " aus " + label(s.pom), file(s.pom), s.line, level + 1);
            }
        }
        for (String name : v.unresolved) {
            x.add(Kind.HINT, "${" + name + "} ist in keiner POM der Kette definiert – kommt aus -D" + name
                    + "=…, .mvn/maven.config, settings.xml oder einem Profil", null, -1, 0);
        }
    }

    /** Parent-Kette vom Modul bis zur POM, aus der etwas geerbt wird. */
    private void inheritance(VersionExplanation x, EffectiveModel model, PomFile target, String what) {
        List<PomFile> chain = model.getChain();
        int idx = chain.indexOf(target);
        if (idx <= 0) {
            return;
        }
        for (int i = 0; i < idx; i++) {
            PomFile child = chain.get(i);
            PomFile parent = chain.get(i + 1);
            x.add(Kind.INHERITED, (i == idx - 1 ? what + ": " : "") + "Parent von " + child.getArtifactId() + " ist " + parent.gav(),
                    file(child), child.getParentLine(), 1);
        }
    }

    private void notes(VersionExplanation x, EffectiveModel model, boolean unmanaged) {
        Set<String> profiles = new LinkedHashSet<String>();
        for (PomFile p : model.getChain()) {
            for (String id : p.getProfilesTouchingVersions()) {
                profiles.add(id + " (" + p.getArtifactId() + ")");
            }
        }
        if (!profiles.isEmpty()) {
            x.add(Kind.HINT, "Profile setzen Properties oder dependencyManagement und sind hier nicht ausgewertet: " + profiles, null, -1, 0);
        }
        for (String n : model.getNotes()) {
            x.add(Kind.HINT, n, null, -1, 0);
        }
        if (!unmanaged) {
            return; // Eintrag gefunden: fehlende BOMs ändern daran nichts mehr
        }
        List<ImportStep> missing = model.getMissingImports();
        ImportStep suspect = suspectBom(model, x.getGroupId());
        if (suspect != null) {
            x.add(Kind.HINT, "BOM " + suspect.declaration.ga() + ":" + suspect.version.value + " (importiert in " + label(suspect.declaredIn)
                            + ") fehlt im lokalen Repository und verwaltet vermutlich " + x.getGroupId() + ". Einmal bauen, dann erneut fragen.",
                    file(suspect.declaredIn), suspect.declaration.getVersionLine(), 0);
        }
        int others = missing.size() - (suspect == null ? 0 : 1);
        if (others > 0) {
            StringBuilder names = new StringBuilder();
            int shown = 0;
            for (ImportStep s : missing) {
                if (s != suspect && shown < 3) {
                    names.append(shown++ == 0 ? "" : ", ").append(s.declaration.getArtifactId()).append(':').append(s.version.value);
                }
            }
            x.add(Kind.HINT, (others == 1 ? "Ein importiertes BOM fehlt" : others + " importierte BOMs fehlen") + " im lokalen Repository ("
                    + names + (others > 3 ? ", …" : "") + "); ihre Einträge sind nicht berücksichtigt.", null, -1, 0);
        }
    }

    /** Fehlendes BOM, das dem groupId nach am ehesten die gesuchte Dependency verwaltet. */
    private static ImportStep suspectBom(EffectiveModel model, String groupId) {
        for (ImportStep s : model.getMissingImports()) {
            String bomGroup = s.declaration.getGroupId();
            if (bomGroup.equals(groupId) || groupId.startsWith(bomGroup + ".") || bomGroup.startsWith(groupId + ".")) {
                return s;
            }
        }
        return null;
    }

    private static String managedSummary(EffectiveModel model, Managed managed, String version) {
        if (managed.via.isEmpty()) {
            return "Version " + version + ": aus dependencyManagement in " + managed.declaredIn.gav()
                    + (managed.declaredIn == model.getPom() ? " (das Modul selbst)." : " (geerbt).");
        }
        ImportStep first = managed.via.get(0);
        ImportStep last = managed.via.get(managed.via.size() - 1);
        StringBuilder sb = new StringBuilder("Version ").append(version).append(": aus dem BOM ")
                .append(last.declaration.ga()).append(':').append(last.version.value);
        if (last.bom != null && managed.declaredIn != last.bom) {
            sb.append(" (Eintrag in dessen Parent ").append(managed.declaredIn.gav()).append(')');
        }
        if (managed.via.size() > 1) {
            sb.append(", importiert über ").append(first.declaration.ga()).append(':').append(first.version.value);
        }
        if (first.declaredIn != model.getPom()) {
            sb.append(managed.via.size() > 1 ? "; " : ", ").append("Import geerbt von ").append(first.declaredIn.gav());
        }
        return sb.append('.').toString();
    }

    private static String versionTag(String raw) {
        return raw == null ? "(ohne <version>)" : "<version>" + raw + "</version>";
    }

    private String label(PomFile pom) {
        Path f = pom.getFile();
        if (projectRoot != null && f.startsWith(projectRoot)) {
            return pom.getArtifactId() + " (" + projectRoot.relativize(f) + ")";
        }
        return pom.gav();
    }

    private static String file(PomFile pom) {
        return pom.getFile().toString();
    }
}
