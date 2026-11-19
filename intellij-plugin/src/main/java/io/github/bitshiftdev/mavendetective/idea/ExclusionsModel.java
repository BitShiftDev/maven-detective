package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Rechenlogik der Seite „Exclusions“ (A4) ohne Swing: Filter, Suche, Sprungziel, angekreuzte → Fixes. */
final class ExclusionsModel {

    private ExclusionsModel() {
    }

    /** Sichtbar: ohne {@code withAll} nur entfernbare (veraltet/überflüssig), dazu der Suchtext. */
    static @NotNull PanelFilter.Filtered<ExclusionAudit.Finding> visible(@NotNull ExclusionAudit audit, boolean withAll,
                                                                      @Nullable String search) {
        return PanelFilter.apply(audit.getFindings(), f -> withAll || f.isRemovable(), search, ExclusionsModel::matches);
    }

    /** Suche über Dependency, Exclusion und das Modul der POM; {@code q} ist klein geschrieben. */
    static boolean matches(@NotNull ExclusionAudit.Finding f, @NotNull String q) {
        return PanelFilter.contains(f.getDependency().ga(), q) || PanelFilter.contains(f.getExclusion().ga(), q)
                || PanelFilter.contains(f.getPom().getArtifactId(), q);
    }

    /** Befund an {@code file:line} (Sprung aus Übersicht oder Editor), sonst {@code null}. */
    static @Nullable ExclusionAudit.Finding at(@NotNull ExclusionAudit audit, @NotNull String file, int line) {
        for (ExclusionAudit.Finding f : audit.getFindings()) {
            if (f.getFile().equals(file) && f.getLine() == line) {
                return f;
            }
        }
        return null;
    }

    /** Entfernbare der Gruppe (Gruppenzeile „2 von 3 entfernbar“). */
    static int removable(@NotNull List<ExclusionAudit.Finding> items) {
        int n = 0;
        for (ExclusionAudit.Finding f : items) {
            if (f.isRemovable()) {
                n++;
            }
        }
        return n;
    }

    /** Fixes der angekreuzten Befunde; nicht entfernbare tragen keinen. */
    static @NotNull List<FixAction> fixes(@NotNull Collection<ExclusionAudit.Finding> checked) {
        List<FixAction> out = new ArrayList<>();
        for (ExclusionAudit.Finding f : checked) {
            FixAction fix = f.fix();
            if (fix != null) {
                out.add(fix);
            }
        }
        return out;
    }
}
