package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.ImportStep;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Interpolated;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kurze Antworten für den Editor (UI-Plan U4): Für jede {@code <dependency>} einer POM ohne
 * {@code <version>} die Version, die {@code dependencyManagement} ihr gibt, und woher – als Inlay
 * „2.18.2 · jackson-bom“ hinter der Dependency. Liest nur POMs (Projekt und lokales Repository).
 */
public final class PomHints {

    /** Eine verwaltete Version für eine {@code <dependency>} ohne eigene Version. */
    public static final class ManagedVersion {
        private final PomFile.Dep dependency;
        private final String version;
        private final String source;
        private final String description;
        private final String file;
        private final int line;

        ManagedVersion(PomFile.Dep dependency, String version, String source, String description, String file, int line) {
            this.dependency = dependency;
            this.version = version;
            this.source = source;
            this.description = description;
            this.file = file;
            this.line = line;
        }

        /** Die {@code <dependency>} in der betrachteten POM. */
        public PomFile.Dep getDependency() { return dependency; }
        /** Aufgelöste Version, z. B. {@code 2.18.2}. */
        public String getVersion() { return version; }
        /** Woher, kurz: BOM- oder Parent-artifactId, sonst {@code dependencyManagement} (das Modul selbst). */
        public String getSource() { return source; }
        /** Ein Satz für den Tooltip. */
        public String getDescription() { return description; }
        /** POM mit dem verwalteten Eintrag (Sprungziel). */
        public String getFile() { return file; }
        /** Zeile der Version im Eintrag, ab 1. */
        public int getLine() { return line; }

        /** Text des Inlays. */
        public String label() {
            return version + " · " + source;
        }
    }

    private PomHints() {
    }

    /**
     * Verwaltete Versionen für die {@code <dependency>}-Einträge direkt unter {@code project/dependencies}
     * von {@code pom}, die keine {@code <version>} haben. Ohne passenden Eintrag oder mit nicht
     * auflösbarer Version (Property fehlt, BOM fehlt lokal) gibt es keinen Hinweis – lieber keiner als ein falscher.
     */
    public static List<ManagedVersion> managedVersions(PomLocator locator, PomFile pom) {
        List<ManagedVersion> out = new ArrayList<ManagedVersion>();
        List<PomFile.Dep> withoutVersion = new ArrayList<PomFile.Dep>();
        for (PomFile.Dep d : pom.getDependencies()) {
            if (d.getVersion() == null && d.getGroupId() != null && d.getArtifactId() != null) {
                withoutVersion.add(d);
            }
        }
        if (withoutVersion.isEmpty()) {
            return out;
        }
        EffectiveModel model = locator.model(pom);
        for (PomFile.Dep d : withoutVersion) {
            String groupId = d.getGroupId();
            if (groupId.contains("${")) {
                Interpolated g = model.interpolate(groupId);
                if (!g.isResolved()) {
                    continue;
                }
                groupId = g.value;
            }
            Managed m = model.getManaged().get(groupId + ":" + d.getArtifactId() + ":" + d.getType()
                    + (d.getClassifier() == null || d.getClassifier().isEmpty() ? "" : ":" + d.getClassifier()));
            if (m == null) {
                m = model.managed(groupId, d.getArtifactId());
            }
            if (m == null || m.dep.getVersion() == null) {
                continue;
            }
            Interpolated v = m.version();
            if (!v.isResolved() || v.value.isEmpty()) {
                continue;
            }
            out.add(new ManagedVersion(d, v.value, source(model, m), description(model, m, v.value),
                    m.declaredIn.getFile().toString(), m.dep.getVersionLine()));
        }
        return Collections.unmodifiableList(out);
    }

    private static String source(EffectiveModel model, Managed m) {
        if (!m.via.isEmpty()) {
            return m.via.get(m.via.size() - 1).declaration.getArtifactId();
        }
        return m.declaredIn == model.getPom() ? "dependencyManagement" : m.declaredIn.getArtifactId();
    }

    private static String description(EffectiveModel model, Managed m, String version) {
        if (m.via.isEmpty()) {
            return m.declaredIn == model.getPom() ? Texts.t("hint.managed.self", version)
                    : Texts.t("hint.managed.parent", version, m.declaredIn.gav());
        }
        ImportStep first = m.via.get(0);
        ImportStep last = m.via.get(m.via.size() - 1);
        StringBuilder sb = new StringBuilder(Texts.t("hint.bom", version, last.declaration.ga() + ":" + last.version.value));
        if (m.via.size() > 1) {
            sb.append(", ").append(Texts.t("why.summary.bom.via", first.declaration.ga()));
        }
        if (first.declaredIn != model.getPom()) {
            sb.append(" (").append(Texts.t("why.summary.bom.inherited", first.declaredIn.getArtifactId())).append(')');
        }
        return sb.toString();
    }
}
