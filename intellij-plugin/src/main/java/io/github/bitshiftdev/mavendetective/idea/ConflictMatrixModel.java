package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Rechenlogik der Seite „Konflikte“ (A2) ohne Swing: Filter, Auswahl über Neuberechnungen, Versionen zum Festlegen. */
final class ConflictMatrixModel {

    private ConflictMatrixModel() {
    }

    /** Sichtbare Zeilen: mit {@code onlyDivergent} nur abweichende, dazu der Suchtext in {@code g:a}. */
    static @NotNull PanelFilter.Filtered<ConflictMatrix.Row> visible(@NotNull ConflictMatrix matrix, boolean onlyDivergent,
                                                                  @Nullable String search) {
        return PanelFilter.apply(matrix.getRows(), r -> !onlyDivergent || r.isDivergent(), search,
                (r, q) -> PanelFilter.contains(r.ga(), q));
    }

    /**
     * Sprung auf {@code ga} (aus Übersicht, Editor, „Warum diese Version?“): die Zeile und ob der Filter „nur
     * abweichende“ danach noch gilt – eine gleichförmige Zeile wäre sonst unsichtbar.
     */
    record Preselect(@Nullable ConflictMatrix.Row row, boolean onlyDivergent) {
    }

    static @NotNull Preselect preselect(@NotNull ConflictMatrix matrix, @NotNull String ga, boolean onlyDivergent) {
        for (ConflictMatrix.Row r : matrix.getRows()) {
            if (r.ga().equals(ga)) {
                return new Preselect(r, onlyDivergent && r.isDivergent());
            }
        }
        return new Preselect(null, onlyDivergent);
    }

    /**
     * Wo die zuvor gewählte Zeile jetzt steht: dieselbe Zeile, sonst (nach Neuberechnung) die mit demselben
     * {@code g:a}; -1, wenn sie ausgeblendet ist.
     */
    static int indexOf(@NotNull List<ConflictMatrix.Row> rows, @Nullable ConflictMatrix.Row before) {
        if (before == null) {
            return -1;
        }
        int same = rows.indexOf(before);
        if (same >= 0) {
            return same;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).ga().equals(before.ga())) {
                return i;
            }
        }
        return -1;
    }

    /** Versionen zur Auswahl beim Festlegen, die höchste zuerst. */
    static @NotNull List<String> pinChoices(@NotNull ConflictMatrix.Row row) {
        List<String> out = new ArrayList<>(row.versions());
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Versionen, die das Kontextmenü einer Zelle zum Festlegen anbietet: die der Zelle, dazu der Vorschlag, wenn er
     * abweicht. Leer, wenn es keinen gemeinsamen Parent zum Festlegen gibt.
     *
     * @param cellVersion Version der Zelle unter der Maus ({@code null}: Zeilenkopf)
     */
    static @NotNull List<String> pinOffers(@NotNull ConflictMatrix.Row row, @Nullable String cellVersion) {
        ConflictMatrix.Pin p = row.getPin();
        List<String> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        if (cellVersion != null) {
            out.add(cellVersion);
        }
        if (!p.getVersion().equals(cellVersion)) {
            out.add(p.getVersion());
        }
        return out;
    }
}
