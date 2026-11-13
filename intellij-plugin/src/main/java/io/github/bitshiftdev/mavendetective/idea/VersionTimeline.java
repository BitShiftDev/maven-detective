package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.WrapLayout;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Station;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import io.github.bitshiftdev.mavendetective.idea.ui.TrackingPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Antwort auf „Warum diese Version?“ als senkrechte Zeitleiste (UI-Plan U5, A1): oben die Antwort in
 * einem Satz und der Pfad im Baum als Breadcrumb (jedes Glied erklärt seine eigene Version), darunter
 * je Station ein Knoten mit Icon für die Art (Modul, Parent, BOM, Property …), Text und Sprung zu
 * Datei:Zeile; Details einer Station hängen eingerückt darunter. Verdrängte Versionen stehen
 * durchgestrichen da, Hinweise klein am Ende.
 */
final class VersionTimeline extends TrackingPanel {

    private final Project project;

    VersionTimeline(@NotNull Project project) {
        super(new VerticalLayout(JBUI.scale(6)));
        this.project = project;
        setBorder(JBUI.Borders.empty(10, 12));
    }

    /** Nur ein Satz (Hinweis, Ladezustand, Fehler). */
    void showMessage(@Nls @NotNull String text) {
        removeAll();
        add(DiagnosisCard.text(text, JBFont.label()));
        refresh();
    }

    /**
     * @param tree    Baum des Moduls (für den Breadcrumb), {@code null} = keiner
     * @param onCrumb Klick auf ein Glied des Pfads
     */
    void show(@NotNull VersionExplanation x, @Nullable DepNode tree, @NotNull Consumer<DepNode> onCrumb) {
        removeAll();
        SimpleColoredComponent title = new SimpleColoredComponent();
        title.setIcon(AllIcons.Nodes.PpLib);
        title.append(x.getGroupId() + ":" + x.getArtifactId(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null));
        if (x.getVersion() != null) {
            title.append("  " + x.getVersion(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        }
        title.setFont(JBFont.h4());
        title.getAccessibleContext().setAccessibleName(x.getGroupId() + ":" + x.getArtifactId() + " " + (x.getVersion() == null ? "" : x.getVersion()));
        add(title);
        if (x.getSummary() != null) {
            add(DiagnosisCard.text(x.getSummary(), JBFont.label().asBold()));
        }
        DepNode included = tree == null ? null : tree.findIncluded(x.getGroupId(), x.getArtifactId());
        if (included != null) {
            add(breadcrumb(tree, included, onCrumb));
        }

        List<List<Station>> groups = group(x.getStations());
        JPanel line = new JPanel(new VerticalLayout(0));
        line.setOpaque(false);
        line.setBorder(JBUI.Borders.emptyTop(6));
        for (int i = 0; i < groups.size(); i++) {
            line.add(node(groups.get(i), i == 0, i == groups.size() - 1));
        }
        if (!groups.isEmpty()) {
            add(line);
        }
        List<Station> hints = x.hints();
        if (!hints.isEmpty()) {
            JPanel h = new JPanel(new VerticalLayout(JBUI.scale(2)));
            h.setOpaque(false);
            h.setBorder(JBUI.Borders.emptyTop(6));
            for (Station s : hints) {
                h.add(row(AllIcons.General.Information, small(s.getText()), link(s)));
            }
            add(h);
        }
        refresh();
    }

    /** Hauptstationen (Ebene 0) mit ihren Details (Ebene 1) darunter. */
    static @NotNull List<List<Station>> group(@NotNull List<Station> stations) {
        List<List<Station>> out = new ArrayList<>();
        for (Station s : stations) {
            if (s.getKind() == Station.Kind.HINT) {
                continue;
            }
            if (s.getLevel() == 0 || out.isEmpty()) {
                out.add(new ArrayList<>());
            }
            out.get(out.size() - 1).add(s);
        }
        return out;
    }

    /**
     * Version, die eine {@link Station.Kind#CONFLICT}-Station verdrängt nennt: Der Kern schreibt
     * „Verdrängt: 1.2 über a:b:1 → … – Grund“. {@code null}, wenn der Text anders aussieht.
     */
    static @Nullable String displacedVersion(@NotNull String text) {
        String prefix = "Verdrängt: ";
        int over = text.indexOf(" über ");
        return text.startsWith(prefix) && over > prefix.length() ? text.substring(prefix.length(), over) : null;
    }

    private JComponent breadcrumb(DepNode tree, DepNode included, Consumer<DepNode> onCrumb) {
        JPanel crumbs = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        crumbs.setOpaque(false);
        crumbs.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("why.path"));
        JBLabel head = new JBLabel(tree.getArtifactId(), AllIcons.Nodes.Module, JBLabel.LEFT);
        head.setToolTipText(tree.gav());
        crumbs.add(head);
        for (DepNode n : included.path()) {
            crumbs.add(new JBLabel("›"));
            String text = n.getArtifactId() + " " + n.getVersion();
            if (n == included) {
                JBLabel last = new JBLabel(text);
                last.setFont(JBFont.label().asBold());
                last.setToolTipText(n.gav());
                crumbs.add(last);
            } else {
                ActionLink link = new ActionLink(text, (ActionListener) e -> onCrumb.accept(n));
                link.setToolTipText(MavenDetectiveBundle.message("why.crumb.tooltip", n.gav()));
                crumbs.add(link);
            }
        }
        return crumbs;
    }

    /** Ein Knoten der Zeitleiste: Schiene mit Icon links, rechts Text, Sprung und Details. */
    private JComponent node(List<Station> group, boolean first, boolean last) {
        Station main = group.get(0);
        JPanel content = new JPanel(new VerticalLayout(JBUI.scale(2)));
        content.setOpaque(false);
        content.setBorder(JBUI.Borders.emptyBottom(last ? 0 : 10));
        content.add(stationText(main, false));
        for (Station s : group.subList(1, group.size())) {
            content.add(row(WhyThisVersionPopup.icon(s.getKind()), stationText(s, true), link(s)));
        }
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        row.setOpaque(false);
        row.add(new Rail(WhyThisVersionPopup.icon(main.getKind()), first, last), BorderLayout.WEST);
        row.add(content, BorderLayout.CENTER);
        JComponent link = link(main);
        if (link != null) {
            JPanel east = new JPanel(new BorderLayout());
            east.setOpaque(false);
            east.add(link, BorderLayout.NORTH);
            row.add(east, BorderLayout.EAST);
        }
        row.getAccessibleContext().setAccessibleName(main.getText());
        return row;
    }

    /** Text einer Station; bei Verdrängten die Version durchgestrichen vorweg. */
    private static JComponent stationText(Station s, boolean small) {
        String version = s.getKind() == Station.Kind.CONFLICT ? displacedVersion(s.getText()) : null;
        if (version == null) {
            return small ? small(s.getText()) : DiagnosisCard.text(s.getText(), JBFont.label());
        }
        JPanel p = new JPanel(new VerticalLayout(0));
        p.setOpaque(false);
        SimpleColoredComponent head = new SimpleColoredComponent();
        head.append(version, new SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getContextHelpForeground()));
        head.append("  " + MavenDetectiveBundle.message("why.displaced"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        head.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("why.displaced.accessible", version));
        head.setIpad(JBUI.emptyInsets());
        p.add(head);
        p.add(small ? small(rest(s.getText(), version)) : DiagnosisCard.text(rest(s.getText(), version), JBFont.label()));
        return p;
    }

    /** „über a → b – Grund“ ohne das vorangestellte „Verdrängt: 1.2“. */
    private static String rest(String text, String version) {
        return text.substring(("Verdrängt: " + version + " ").length());
    }

    private static JComponent small(@Nls String text) {
        JComponent t = DiagnosisCard.text(text, JBFont.small());
        t.setForeground(UIUtil.getContextHelpForeground());
        return t;
    }

    private static JComponent row(Icon icon, JComponent text, @Nullable JComponent link) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row.setOpaque(false);
        JBLabel i = new JBLabel(icon);
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.add(i, BorderLayout.NORTH);
        row.add(west, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        if (link != null) {
            JPanel east = new JPanel(new BorderLayout());
            east.setOpaque(false);
            east.add(link, BorderLayout.NORTH);
            row.add(east, BorderLayout.EAST);
        }
        return row;
    }

    private @Nullable JComponent link(Station s) {
        if (s.getFile() == null) {
            return null;
        }
        String location = Locations.of(s.getFile(), s.getLine());
        ActionLink link = new ActionLink(DiagnosisCard.fileLabel(s.getFile(), s.getLine()), (ActionListener) e -> Locations.open(project, location));
        link.setToolTipText(location);
        return link;
    }

    private void refresh() {
        revalidate();
        repaint();
    }

    /** Senkrechte Linie mit dem Icon der Station; oben bzw. unten offen am ersten bzw. letzten Knoten. */
    private static final class Rail extends JComponent {
        private final Icon icon;
        private final boolean first;
        private final boolean last;

        Rail(Icon icon, boolean first, boolean last) {
            this.icon = icon;
            this.first = first;
            this.last = last;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(20), icon.getIconHeight() + JBUI.scale(4));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int x = getWidth() / 2;
                int iconTop = JBUI.scale(1);
                int iconBottom = iconTop + icon.getIconHeight();
                g2.setColor(JBColor.border());
                if (!first) {
                    g2.fillRect(x - 1, 0, JBUI.scale(2), iconTop);
                }
                if (!last) {
                    g2.fillRect(x - 1, iconBottom + JBUI.scale(2), JBUI.scale(2), getHeight() - iconBottom - JBUI.scale(2));
                }
                icon.paintIcon(this, g2, x - icon.getIconWidth() / 2, iconTop);
            } finally {
                g2.dispose();
            }
        }
    }
}
