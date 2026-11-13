package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Leiste unter einem Detailbereich für die Aktion, die zur Auswahl gehört (Version wählen und
 * festlegen, Exclusion einfügen, übernehmen). Allgemeine Befehle stehen in der Toolbar oben.
 */
public final class FixBar extends JPanel {

    public FixBar(@NotNull JComponent... components) {
        super(new HorizontalLayout(JBUI.scale(8)));
        setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(6, 8)));
        for (JComponent c : components) {
            add(c);
        }
    }

    /** Hauptaktion der Leiste: im Stil des Standardknopfs eines Dialogs. */
    public static @NotNull JButton primary(@NotNull JButton button) {
        button.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, Boolean.TRUE);
        return button;
    }
}
