package io.github.bitshiftdev.mavendetective.idea;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Sichtbare Befunde einer Seite aus Schaltern und Suchtext – ohne Swing, damit die Rechenlogik der Seiten
 * (Modul A) mit JUnit testbar ist. Die Seiten zeigen nur, was diese Klassen und die Modelle daneben liefern.
 */
final class PanelFilter {

    private PanelFilter() {
    }

    /**
     * @param rows   sichtbar, in der Reihenfolge der Eingabe
     * @param hidden durch Schalter oder Suche ausgeblendet
     */
    record Filtered<T>(@NotNull List<T> rows, int hidden) {
    }

    /** Suchtext so, wie {@code matches} ihn bekommt: getrimmt, klein; {@code null} → leer. */
    static @NotNull String query(@Nullable String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Zeigt, was {@code shown} zulässt und – bei nicht leerem Suchtext – {@code matches} mit dem kleingeschriebenen
     * Suchtext erfüllt.
     */
    static <T> @NotNull Filtered<T> apply(@NotNull Collection<T> all, @NotNull Predicate<T> shown, @Nullable String search,
                                          @NotNull BiPredicate<T, String> matches) {
        String q = query(search);
        List<T> rows = new ArrayList<>();
        int hidden = 0;
        for (T t : all) {
            if (shown.test(t) && (q.isEmpty() || matches.test(t, q))) {
                rows.add(t);
            } else {
                hidden++;
            }
        }
        return new Filtered<>(rows, hidden);
    }

    /** Enthält {@code text} den (schon kleingeschriebenen) Suchtext? {@code null} nie. */
    static boolean contains(@Nullable String text, @NotNull String q) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q);
    }
}
