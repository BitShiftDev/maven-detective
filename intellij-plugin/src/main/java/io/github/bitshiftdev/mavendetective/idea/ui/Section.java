package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.components.ActionLink;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.idea.MavenDetectiveBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

/**
 * Aufklappbarer Abschnitt: Titel mit Pfeil (▸ zu, ▾ offen) als Link – per Maus und Tastatur
 * (Tab, Leertaste) bedienbar –, darunter eingerückt der Inhalt.
 */
public final class Section extends JPanel {

    private final ActionLink title;
    private final JComponent content;
    private final JPanel inner = new JPanel(new BorderLayout());
    private boolean expanded;

    /** @param onToggle bekommt den neuen Zustand (z. B. um ihn über Karten hinweg zu merken) */
    public Section(@Nls @NotNull String titleText, @NotNull JComponent content, boolean expanded, @NotNull Consumer<Boolean> onToggle) {
        super(new BorderLayout());
        this.content = content;
        setOpaque(false);
        title = new ActionLink(titleText, (ActionListener) e -> {
            setExpanded(!this.expanded);
            onToggle.accept(this.expanded);
        });
        title.setFont(JBFont.label().asBold());
        title.setIconTextGap(JBUI.scale(4));
        inner.setOpaque(false);
        inner.setBorder(JBUI.Borders.empty(6, 20, 0, 0));
        inner.add(content, BorderLayout.CENTER);
        add(title, BorderLayout.NORTH);
        add(inner, BorderLayout.CENTER);
        setExpanded(expanded);
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        title.setIcon(expanded ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
        inner.setVisible(expanded);
        title.getAccessibleContext().setAccessibleDescription(MavenDetectiveBundle.message(expanded ? "section.expanded" : "section.collapsed"));
        revalidate();
        repaint();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public @NotNull JComponent getContent() {
        return content;
    }
}
