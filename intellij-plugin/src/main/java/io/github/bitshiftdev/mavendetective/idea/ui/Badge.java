package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Kleines Etikett mit abgerundetem Hintergrund, z. B. die Sicherheit einer Diagnose („sicher“,
 * „wahrscheinlich“). Die Aussage steht als Text da, die Farbe unterstreicht sie nur.
 */
public final class Badge extends JBLabel {

    private final Color background;

    public Badge(@Nls @NotNull String text, @NotNull Color background) {
        super(text);
        this.background = background;
        setFont(JBFont.small().asBold());
        setBorder(JBUI.Borders.empty(1, 8));
        setOpaque(false);
        getAccessibleContext().setAccessibleName(text);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(background);
            int arc = getHeight();
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
