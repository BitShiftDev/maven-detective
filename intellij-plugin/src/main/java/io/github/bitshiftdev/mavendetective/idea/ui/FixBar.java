package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;

/**
 * Leiste unter einem Detailbereich für die Aktion, die zur Auswahl gehört (Version wählen und
 * festlegen, Exclusion einfügen, übernehmen). Allgemeine Befehle stehen in der Toolbar oben. Bricht im
 * schmalen Tool-Window um, statt Knöpfe abzuschneiden; die Hauptaktion bleibt vorn.
 */
public final class FixBar extends JPanel {

    public FixBar(@NotNull JComponent... components) {
        // FlowLayout setzt hgap links und vgap oben/unten selbst: zusammen wieder 8 px links, 6 px oben/unten
        super(new WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)));
        setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(2, 0)));
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
