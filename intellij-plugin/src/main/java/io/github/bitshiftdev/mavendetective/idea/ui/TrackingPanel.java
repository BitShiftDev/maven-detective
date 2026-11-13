package io.github.bitshiftdev.mavendetective.idea.ui;

import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

/**
 * Inhalt eines Scroll-Bereichs, der die Breite des sichtbaren Ausschnitts übernimmt: Text bricht um,
 * statt nach rechts aus dem Tool-Window zu laufen; gescrollt wird nur senkrecht.
 */
public class TrackingPanel extends JPanel implements Scrollable {

    public TrackingPanel(LayoutManager layout) {
        super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(16, visible.height - 32);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        // Kurzer Inhalt (z. B. leerer Zustand mit zentriertem Text) füllt den Ausschnitt
        return getParent() instanceof JViewport viewport && getPreferredSize().height < viewport.getHeight();
    }
}
