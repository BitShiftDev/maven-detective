package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Rechenlogik der Seite „Doppelte Klassen“ (A3) ohne Swing: Filter, Suche, Empfehlung → Exclusions. */
final class DuplicateClassesModel {

    private DuplicateClassesModel() {
    }

    /** Sichtbar: Testfunde nur mit {@code withTests}, inhaltsgleiche nur mit {@code withIdentical}, dazu der Suchtext. */
    static @NotNull PanelFilter.Filtered<DuplicateClasses.Clash> visible(@NotNull DuplicateClasses result, boolean withTests,
                                                                      boolean withIdentical, @Nullable String search) {
        return PanelFilter.apply(result.clashes(), c -> (withTests || c.isMain()) && (withIdentical || !c.isIdentical()),
                search, DuplicateClassesModel::matches);
    }

    /** Suche über Modul, JARs ({@code g:a}) und Klassennamen; {@code q} ist klein geschrieben. */
    static boolean matches(@NotNull DuplicateClasses.Clash c, @NotNull String q) {
        if (PanelFilter.contains(c.getModule().getName(), q)) {
            return true;
        }
        for (ModuleClasspath.Entry e : c.getJars()) {
            if (PanelFilter.contains(e.ga(), q)) {
                return true;
            }
        }
        for (String cls : c.getClasses()) {
            if (PanelFilter.contains(cls, q)) {
                return true;
            }
        }
        return false;
    }

    /** Exclusions, die die Empfehlung umsetzen ({@code drop} raus); leer, wenn es keine gibt. Ankreuzbar = nicht leer. */
    static @NotNull List<FixAction> exclusionsFor(@NotNull DuplicateClasses.Clash c) {
        List<FixAction> out = new ArrayList<>();
        if (c.getDrop() != null) {
            for (FixAction f : c.fixesFor(c.getDrop())) {
                if (FixAction.ADD_EXCLUSION.equals(f.getType())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /**
     * Exclusions für alle angekreuzten Funde: dieselbe Exclusion bei derselben Dependency derselben POM nur einmal
     * ({@code PomEditor} lehnt Duplikate ab). Ihre Zahl steht auf dem Knopf.
     */
    static @NotNull List<FixAction> exclusionsFor(@NotNull Collection<DuplicateClasses.Clash> checked) {
        List<FixAction> fixes = new ArrayList<>();
        for (DuplicateClasses.Clash c : checked) {
            for (FixAction f : exclusionsFor(c)) {
                if (!containsSame(fixes, f)) {
                    fixes.add(f);
                }
            }
        }
        return fixes;
    }

    private static boolean containsSame(List<FixAction> fixes, FixAction f) {
        for (FixAction g : fixes) {
            if (Objects.equals(g.param("file"), f.param("file")) && Objects.equals(g.param("artifact"), f.param("artifact"))
                    && Objects.equals(g.param("exclusion"), f.param("exclusion"))) {
                return true;
            }
        }
        return false;
    }

    /** Funde mit verschiedenem Inhalt im Hauptcode – die gefährlichen (Gruppenzeile). */
    static int differing(@NotNull List<DuplicateClasses.Clash> items) {
        int n = 0;
        for (DuplicateClasses.Clash c : items) {
            if (c.isMain() && !c.isIdentical()) {
                n++;
            }
        }
        return n;
    }

    /** Nicht lesbare JARs über alle Module (Statuszeile). */
    static int unreadable(@NotNull DuplicateClasses result) {
        int n = 0;
        for (DuplicateClasses.ModuleResult m : result.getModules()) {
            n += m.getUnreadable().size();
        }
        return n;
    }
}
