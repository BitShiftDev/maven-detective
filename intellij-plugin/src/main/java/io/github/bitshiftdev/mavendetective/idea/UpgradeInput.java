package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel;
import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Eingaben des Upgrade-Simulators (A6) ohne Swing: Vorschläge, neuere lokale Version, Anfrage aus den Feldern. */
final class UpgradeInput {

    private UpgradeInput() {
    }

    /**
     * Was sich heben lässt, mit heutiger Version: alles im Classpath (eingebundene Knoten der Bäume), Parents und
     * importierte BOMs der Projekt-POMs. Sortiert; bei doppeltem {@code g:a} gilt der erste Fund.
     */
    static @NotNull Map<String, String> suggestions(@NotNull Collection<DepNode> trees, @NotNull List<PomFile> poms,
                                                    @NotNull PomLocator locator) {
        Map<String, String> found = new TreeMap<>();
        for (DepNode tree : trees) {
            collect(tree, found);
        }
        for (PomFile p : poms) {
            if (p.hasParent()) {
                found.putIfAbsent(p.getParentGroupId() + ":" + p.getParentArtifactId(), p.getParentVersion());
            }
            for (EffectiveModel.ImportStep s : locator.model(p).getImports()) {
                found.putIfAbsent(s.declaration.ga(), s.version.value);
            }
        }
        return found;
    }

    private static void collect(DepNode n, Map<String, String> out) {
        for (DepNode c : n.getChildren()) {
            if (c.getState() == DepNode.State.INCLUDED) {
                out.putIfAbsent(c.ga(), c.getVersion());
                collect(c, out);
            }
        }
    }

    /** {@code g:a} aus dem Feld, sonst {@code null} (Property, unvollständig, mehr Teile). */
    static String @Nullable [] ga(@NotNull String text) {
        String[] p = text.trim().split(":", -1);
        return p.length == 2 && !p[0].isEmpty() && !p[1].isEmpty() ? p : null;
    }

    /** Höchste lokale Version über {@code current}; {@code null}, wenn es keine gibt oder {@code current} unbekannt ist. */
    static @Nullable String newer(@NotNull List<String> local, @Nullable String current) {
        if (current == null || local.isEmpty()) {
            return null;
        }
        String best = MavenVersion.max(local);
        return best != null && MavenVersion.compare(best, current) > 0 ? best : null;
    }

    /**
     * Anfrage aus den Feldern „Was“ ({@code g:a}, {@code ${property}} oder Property-Name) und „Version“.
     *
     * @param manage „unter dependencyManagement festlegen“ (gilt nicht für Properties)
     * @throws IllegalArgumentException mit einem Satz für die Statuszeile
     */
    static @NotNull UpgradeSimulator.Request request(@Nullable String what, @Nullable String version, boolean manage) {
        String w = what == null ? "" : what.trim();
        String v = version == null ? "" : version.trim();
        if (w.isEmpty() || v.isEmpty()) {
            throw new IllegalArgumentException(MavenDetectiveBundle.message("upgrade.missing.input"));
        }
        if (v.contains("=") || v.contains(":") || v.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(MavenDetectiveBundle.message("upgrade.invalid.version", v));
        }
        UpgradeSimulator.Request r = w.startsWith("${") || !w.contains(":") ? UpgradeSimulator.Request.parse(w + "=" + v)
                : UpgradeSimulator.Request.parse(w + ":" + v);
        return manage && !r.isProperty() ? r.pinned() : r;
    }

    /** Wie ein Knoten im Vorher/Nachher steht: direkt mit Scope, sonst der Pfad dorthin. */
    static @NotNull String path(@NotNull DepNode n) {
        String scope = n.getScope() == null || "compile".equals(n.getScope()) ? null : n.getScope();
        return n.depth() <= 1 ? n.gav() + " (" + MavenDetectiveBundle.message("upgrade.editor.direct") + (scope == null ? "" : ", " + scope) + ")"
                : n.pathText() + (scope == null ? "" : " (" + scope + ")");
    }
}
