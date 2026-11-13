package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.event.HyperlinkEvent;
import java.util.function.Predicate;

/**
 * Detailbereich mit HTML in den Farben und Schriften des Themes. Links auf {@code pfad:zeile}
 * öffnen die Datei; eigene Ziele (z. B. {@code md:setup}) behandelt {@code internal}, wenn es
 * {@code true} liefert.
 */
public final class HtmlView {

    private final JEditorPane pane = new JEditorPane();
    private final JBScrollPane scroll;

    public HtmlView(@NotNull Project project) {
        this(project, null);
    }

    public HtmlView(@NotNull Project project, @Nullable Predicate<String> internal) {
        pane.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        pane.setEditable(false);
        pane.setBorder(JBUI.Borders.empty(8, 12));
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || e.getDescription() == null) {
                return;
            }
            if (internal == null || !internal.test(e.getDescription())) {
                Locations.open(project, e.getDescription());
            }
        });
        scroll = new JBScrollPane(pane);
        scroll.setBorder(JBUI.Borders.empty());
    }

    public @NotNull JComponent component() {
        return scroll;
    }

    /** Inhalt des {@code <body>} setzen und nach oben scrollen. */
    public void show(@NotNull String body) {
        pane.setText(Html.page(body));
        pane.setCaretPosition(0);
    }
}
