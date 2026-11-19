package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.ClassUsages;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage;
import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Rechenlogik der Seite „Nutzung“ (A5) ohne Swing: Filter, Suche, Auswahl → POM-Fixes oder Sprung. */
final class DependencyUsageModel {

    private DependencyUsageModel() {
    }

    /** Sichtbar: über Starter nur mit {@code withBundles}, unsichere nur mit {@code withUncertain}, dazu der Suchtext. */
    static @NotNull PanelFilter.Filtered<DependencyUsage.Finding> visible(@NotNull DependencyUsage result, boolean withBundles,
                                                                       boolean withUncertain, @Nullable String search) {
        return PanelFilter.apply(result.findings(), f -> (withBundles || !f.isViaBundle()) && (withUncertain || f.isCertain()),
                search, DependencyUsageModel::matches);
    }

    /** Suche über Modul, {@code g:a} und die genannten Klassen; {@code q} ist klein geschrieben. */
    static boolean matches(@NotNull DependencyUsage.Finding f, @NotNull String q) {
        if (PanelFilter.contains(f.getModule().getName(), q) || PanelFilter.contains(f.ga(), q)) {
            return true;
        }
        for (List<ClassUsages.Use> uses : List.of(f.getMainUses(), f.getTestUses())) {
            for (ClassUsages.Use u : uses) {
                if (PanelFilter.contains(u.getClassName(), q)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Worauf „Beheben“ wirkt: die angekreuzten Befunde, sonst die ausgewählten. */
    static @NotNull List<DependencyUsage.Finding> targets(@NotNull List<DependencyUsage.Finding> checked,
                                                         @NotNull List<DependencyUsage.Finding> selected) {
        return checked.isEmpty() ? selected : checked;
    }

    /** Ankreuzbar: der Befund lässt sich per POM-Änderung beheben. */
    static boolean hasPomFix(@NotNull DependencyUsage.Finding f) {
        for (FixAction fix : f.fixes()) {
            if (PomEditor.isPomFix(fix.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Was „Beheben“ für {@code targets} tut.
     *
     * @param pomFixes alle POM-Änderungen (ein Undo-Schritt)
     * @param open     ohne POM-Änderung: Sprung zur Deklaration (geerbte Dependency) – nur bei genau einem Befund
     */
    record Apply(@NotNull List<FixAction> pomFixes, @Nullable FixAction open) {

        boolean isEnabled() {
            return !pomFixes.isEmpty() || open != null;
        }

        boolean isJump() {
            return pomFixes.isEmpty() && open != null;
        }
    }

    static @NotNull Apply apply(@NotNull List<DependencyUsage.Finding> targets) {
        List<FixAction> pomFixes = new ArrayList<>();
        FixAction open = null;
        for (DependencyUsage.Finding f : targets) {
            for (FixAction fix : f.fixes()) {
                if (PomEditor.isPomFix(fix.getType())) {
                    pomFixes.add(fix);
                } else if (open == null && FixAction.OPEN_FILE.equals(fix.getType())) {
                    open = fix;
                }
            }
        }
        // Mehrere Befunde ohne POM-Änderung: kein Sprung zu einem beliebigen davon
        return new Apply(pomFixes, pomFixes.isEmpty() && targets.size() != 1 ? null : open);
    }

    /**
     * Zahl je Art in der Reihenfolge undeklariert, ungenutzt, nur in Tests.
     *
     * @param withBundles über Starter mitzählen
     */
    static int @NotNull [] counts(@NotNull List<DependencyUsage.Finding> findings, boolean withBundles) {
        int[] n = new int[3];
        for (DependencyUsage.Finding f : findings) {
            if (!withBundles && f.isViaBundle()) {
                continue;
            }
            switch (f.getKind()) {
                case UNDECLARED -> n[0]++;
                case UNUSED -> n[1]++;
                default -> n[2]++;
            }
        }
        return n;
    }
}
