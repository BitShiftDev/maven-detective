package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.RoundedLineBorder;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Kachel der Übersicht: Titel (Link, öffnet die Vertiefung), Wert mit Zustands-Icon, eine Zeile
 * Erklärung und optional eine Aktion („Prüfen“). Ein Klick irgendwo auf die Kachel öffnet ebenfalls;
 * per Tastatur über den Titel-Link. Zustand steht immer auch als Text da, nie nur als Farbe oder Icon.
 */
public final class Tile extends JPanel {

    /** Breite im Raster (unskaliert); in einer einzelnen Spalte füllt die Kachel die Breite ({@link #setTileWidth}). */
    public static final int WIDTH = 250;
    /** Untergrenze für {@link #setTileWidth} (unskaliert). */
    public static final int MIN_WIDTH = 200;

    private final ActionLink title;
    private final JBLabel value = new JBLabel();
    private final JBLabel detail = new JBLabel();
    private final ActionLink action = new ActionLink("");
    private @Nullable Runnable onAction;
    /** Aktuelle Breite (skaliert). */
    private int width = JBUIScale.scale(WIDTH);
    private @Nls @Nullable String detailText;

    public Tile(@Nls @NotNull String titleText, @NotNull Runnable open) {
        super(new VerticalLayout(JBUI.scale(4)));
        title = new ActionLink(titleText, (ActionListener) e -> open.run());
        title.setFont(JBFont.label().asBold());
        value.setFont(JBFont.h3().asBold());
        value.setIconTextGap(JBUI.scale(6));
        detail.setForeground(NamedColorUtil.getInactiveTextColor());
        detail.setFont(JBFont.small());
        action.addActionListener(e -> {
            if (onAction != null) {
                onAction.run();
            }
        });
        action.setVisible(false);
        add(title);
        add(value);
        add(detail);
        add(action);
        setBorder(JBUI.Borders.compound(new RoundedLineBorder(JBColor.border(), JBUI.scale(10)), JBUI.Borders.empty(10, 12)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                open.run();
            }
        });
        getAccessibleContext().setAccessibleName(titleText);
    }

    /**
     * @param icon       Zustand (OK, Warnung, Fehler, läuft, nicht geprüft)
     * @param valueText  Kernaussage, z. B. „3“ oder „rot · 14:02“
     * @param detailText eine Zeile Erklärung (bricht um); {@code null} = leer
     * @param actionText Aktion unter der Erklärung; {@code null} = keine
     */
    public void update(@Nullable Icon icon, @Nls @NotNull String valueText, @Nls @Nullable String detailText,
                       @Nls @Nullable String actionText, @Nullable Runnable actionRun) {
        value.setIcon(icon);
        value.setText(valueText);
        this.detailText = detailText;
        detail.setText(detailText == null ? "" : wrap(detailText));
        detail.setToolTipText(detailText);
        onAction = actionRun;
        action.setText(actionText == null ? "" : actionText);
        action.setVisible(actionText != null && actionRun != null);
        getAccessibleContext().setAccessibleDescription(valueText + (detailText == null ? "" : ". " + detailText));
        revalidate();
        repaint();
    }

    /**
     * Breite in Pixeln (skaliert, mindestens {@link #MIN_WIDTH}): im Raster {@link #WIDTH}, im schmalen
     * Tool-Window die ganze Spalte. Bricht die Erklärung für die neue Breite neu um.
     */
    public void setTileWidth(int scaledWidth) {
        int w = Math.max(JBUIScale.scale(MIN_WIDTH), scaledWidth);
        if (w == width) {
            return;
        }
        width = w;
        detail.setText(detailText == null ? "" : wrap(detailText));
        revalidate();
    }

    /** Feste Breite je Kachel, damit Text umbricht und die Kacheln einer Zeile gleich groß sind. */
    private String wrap(String text) {
        return "<html><body style='width:" + (width - JBUIScale.scale(30)) + "px'>" + Html.esc(text) + "</body></html>";
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(width, Math.max(d.height, JBUI.scale(110)));
    }
}
