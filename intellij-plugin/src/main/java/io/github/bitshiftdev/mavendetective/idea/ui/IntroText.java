package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.ComponentWithEmptyText;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.StatusText;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Leerer Zustand mit Erklärung ({@link DetectivePanel#intro}), umbrochen auf die sichtbare Breite.
 * {@link StatusText} bricht selbst nicht um: lange Sätze liefen im schmalen Tool-Window links und rechts
 * aus dem Bild. Bei jeder Größenänderung wird neu umbrochen, solange der leere Zustand noch dieser ist.
 */
final class IntroText {

    private static final String KEY = "MavenDetective.IntroText";
    private static final String KEY_VIEWPORT = "MavenDetective.IntroText.viewport";
    /** Abstand links und rechts vom Text (unskaliert). */
    private static final int MARGIN = 24;

    private final @Nls String text;
    private final @Nls @Nullable String about;
    private final @Nls @Nullable String link;
    private final @Nullable Runnable action;
    /** Zuletzt gesetzter Text der StatusText – ändert ihn jemand anders, wird nicht mehr umbrochen. */
    private @Nullable String rendered;

    private IntroText(@Nls String text, @Nls @Nullable String about, @Nls @Nullable String link, @Nullable Runnable action) {
        this.text = text;
        this.about = about;
        this.link = link;
        this.action = action;
    }

    static <C extends JComponent & ComponentWithEmptyText> void show(@NotNull C owner, @Nls @NotNull String text,
                                                                    @Nls @Nullable String about, @Nls @Nullable String link,
                                                                    @Nullable Runnable action) {
        IntroText intro = new IntroText(text, about, link, action);
        if (owner.getClientProperty(KEY) == null) {
            ComponentAdapter listener = new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (owner.getClientProperty(KEY) instanceof IntroText current
                            && current.rendered != null && current.rendered.equals(owner.getEmptyText().getText())) {
                        current.render(owner);
                    }
                }
            };
            owner.addComponentListener(listener);
            // Leere Tabellen ohne Auto-Resize sind schmaler als der Ausschnitt: dessen Breite zählt. Der
            // Ausschnitt kommt oft erst nach dem ersten intro() dazu (Scroll-Bereich entsteht danach).
            viewportListener(owner, listener);
            owner.addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) {
                    viewportListener(owner, listener);
                }
            });
        }
        owner.putClientProperty(KEY, intro);
        intro.render(owner);
    }

    private static void viewportListener(@NotNull JComponent owner, @NotNull ComponentAdapter listener) {
        if (owner.getParent() instanceof JViewport viewport && owner.getClientProperty(KEY_VIEWPORT) != viewport) {
            viewport.addComponentListener(listener);
            owner.putClientProperty(KEY_VIEWPORT, viewport);
        }
    }

    private <C extends JComponent & ComponentWithEmptyText> void render(@NotNull C owner) {
        StatusText target = owner.getEmptyText();
        Font font = target.getComponent().getFont() != null ? target.getComponent().getFont() : owner.getFont();
        FontMetrics fm = owner.getFontMetrics(font);
        int max = width(owner) - 2 * JBUI.scale(MARGIN);
        List<String> head = wrap(text, max, fm::stringWidth);
        target.setText(head.get(0));
        for (String line : head.subList(1, head.size())) {
            target.appendLine(line, StatusText.DEFAULT_ATTRIBUTES, null);
        }
        if (about != null) {
            for (String paragraph : about.split("\n")) {
                for (String line : wrap(paragraph, max, fm::stringWidth)) {
                    target.appendLine(line, SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
                }
            }
        }
        if (link != null && action != null) {
            target.appendLine(link, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, e -> action.run());
        }
        rendered = target.getText();
        owner.repaint();
    }

    /** Sichtbare Breite; vor dem ersten Layout 0 (dann wird nicht umbrochen). */
    private static int width(@NotNull JComponent owner) {
        Component parent = owner.getParent();
        return parent instanceof JViewport viewport ? viewport.getWidth() : owner.getWidth();
    }

    /**
     * Bricht an Leerzeichen um, sodass jede Zeile höchstens {@code max} breit ist; ein einzelnes Wort,
     * das allein zu breit ist, bleibt ganz. {@code max ≤ 0}: nicht umbrechen.
     */
    static @NotNull List<String> wrap(@NotNull String text, int max, @NotNull ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        if (max <= 0 || width.applyAsInt(text) <= max) {
            lines.add(text);
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.isEmpty()) {
                line.append(word);
            } else if (width.applyAsInt(line + " " + word) <= max) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        lines.add(line.toString());
        return lines;
    }
}
