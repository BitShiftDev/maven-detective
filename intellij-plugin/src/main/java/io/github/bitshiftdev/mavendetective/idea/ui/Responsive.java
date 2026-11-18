package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.scale.JBUIScale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.Consumer;

/**
 * Breit oder schmal: entscheidet nach der tatsächlichen Breite einer Komponente, nicht nach dem Anker
 * des Tool-Windows. Damit sind links/rechts angeheftet, schwebend, im Split und ein schmal gezogenes
 * unteres Fenster gleich abgedeckt. Zwischen den Schwellen liegt eine Hysterese, damit das Layout
 * beim Ziehen am Rand nicht hin und her springt.
 */
public final class Responsive {

    public enum Mode { WIDE, NARROW }

    /** Unter dieser Breite (unskaliert) wird ein breites Layout schmal. */
    static final int NARROW_BELOW = 540;
    /** Ab dieser Breite (unskaliert) wird ein schmales Layout wieder breit. */
    static final int WIDE_FROM = 600;
    /** Anhang am Proportions-Key eines Splitters für den schmalen Modus. */
    static final String NARROW_SUFFIX = ".narrow";

    private final Consumer<Mode> onChange;
    private @Nullable Mode mode;

    private Responsive(@NotNull Consumer<Mode> onChange) {
        this.onChange = onChange;
    }

    /**
     * Beobachtet die Breite von {@code root}; {@code onChange} läuft (EDT) beim ersten Layout und bei
     * jedem Wechsel des Modus, nie bei gleichbleibendem.
     */
    public static @NotNull Responsive install(@NotNull JComponent root, @NotNull Disposable parent,
                                              @NotNull Consumer<Mode> onChange) {
        Responsive r = new Responsive(onChange);
        ComponentAdapter listener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                r.width(root.getWidth());
            }
        };
        root.addComponentListener(listener);
        Disposer.register(parent, () -> root.removeComponentListener(listener));
        return r;
    }

    /** Aktueller Modus; {@code null}, solange die Komponente noch nicht ausgelegt wurde. */
    public @Nullable Mode mode() {
        return mode;
    }

    public boolean isNarrow() {
        return mode == Mode.NARROW;
    }

    private void width(int width) {
        Mode next = next(mode, width, JBUIScale.scale(NARROW_BELOW), JBUIScale.scale(WIDE_FROM));
        if (next != mode) {
            mode = next;
            onChange.accept(next);
        }
    }

    /**
     * Nächster Modus für eine Breite (skaliert, wie die Schwellen). Ohne bisherigen Modus entscheidet
     * die Mitte zwischen den Schwellen; Breite ≤ 0 (noch nicht ausgelegt) ändert nichts.
     */
    static @Nullable Mode next(@Nullable Mode current, int width, int narrowBelow, int wideFrom) {
        if (width <= 0) {
            return current;
        }
        if (current == null) {
            return width < (narrowBelow + wideFrom) / 2 ? Mode.NARROW : Mode.WIDE;
        }
        if (current == Mode.WIDE && width < narrowBelow) {
            return Mode.NARROW;
        }
        if (current == Mode.NARROW && width >= wideFrom) {
            return Mode.WIDE;
        }
        return current;
    }

    /**
     * Splitter für einen Modus: breit nebeneinander, schmal übereinander. Jeder Modus merkt sich seinen
     * Anteil unter eigenem Key; breit bleibt der bisherige {@code key}, damit gespeicherte Aufteilungen
     * erhalten bleiben.
     */
    public static void splitter(@NotNull JBSplitter splitter, @NotNull String key, float wide, float narrow,
                                @NotNull Mode mode) {
        String k = proportionKey(key, mode);
        if (k.equals(splitter.getSplitterProportionKey()) && splitter.getOrientation() == (mode == Mode.NARROW)) {
            return;
        }
        // Erst den Key wechseln: setProportion speichert sofort, sonst überschriebe es den anderen Modus
        splitter.setSplitterProportionKey(k);
        splitter.setOrientation(mode == Mode.NARROW);
        splitter.setProportion(PropertiesComponent.getInstance().getFloat(k, mode == Mode.NARROW ? narrow : wide));
    }

    static @NotNull String proportionKey(@NotNull String key, @NotNull Mode mode) {
        return mode == Mode.NARROW ? key + NARROW_SUFFIX : key;
    }
}
