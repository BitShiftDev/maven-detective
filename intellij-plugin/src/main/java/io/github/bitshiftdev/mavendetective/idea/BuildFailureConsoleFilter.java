package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.impl.InlayProvider;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Konsole und Build-Fenster: Mavens Zeile {@code [INFO] BUILD FAILURE} wird zum Link „Ursache
 * anzeigen“ – die Diagnose beginnt dort, wo der Nutzer gerade hinschaut. Dahinter steht ein
 * Hinweis-Inlay, damit der Link als Maven Detective erkennbar ist (Inlays selbst sind in der Konsole
 * nicht klickbar, deshalb trägt der Text den Link).
 */
final class BuildFailureConsoleFilter implements Filter, DumbAware {

    /** Maven 3 und 4; ANSI-Farben entfernt die Konsole vor den Filtern. */
    private static final Pattern FAILURE = Pattern.compile("^\\[(?:INFO|ERROR)\\] (BUILD FAILURE)\\s*$");

    private final Project project;

    BuildFailureConsoleFilter(@NotNull Project project) {
        this.project = project;
    }

    /** Registriert in plugin.xml ({@code consoleFilterProvider}). */
    public static final class Provider implements ConsoleFilterProvider, DumbAware {
        @Override
        public Filter @NotNull [] getDefaultFilters(@NotNull Project project) {
            return new Filter[] {new BuildFailureConsoleFilter(project)};
        }
    }

    /** Spalten von „BUILD FAILURE“ in der Zeile ({@code [start, end)}), {@code null}: keine Maven-Fehlerzeile. */
    static int @Nullable [] failureRange(@NotNull String line) {
        if (!line.contains("BUILD FAILURE")) {
            return null;   // schneller Ausstieg: der Filter sieht jede Zeile jeder Konsole
        }
        Matcher m = FAILURE.matcher(line.stripTrailing());
        return m.matches() ? new int[] {m.start(1), m.end(1)} : null;
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        int[] range = failureRange(line);
        if (range == null) {
            return null;
        }
        int lineStart = entireLength - line.length();
        HyperlinkInfo open = p -> Navigator.buildDoctor(p, BuildDoctorPanel::showLatest);
        return new Result(List.of(new LinkWithHint(lineStart + range[0], lineStart + range[1], open)));
    }

    /** Link auf „BUILD FAILURE“ plus Hinweis-Inlay am Ende. */
    private static final class LinkWithHint extends ResultItem implements InlayProvider {
        LinkWithHint(int start, int end, HyperlinkInfo info) {
            super(start, end, info);
        }

        @Override
        public EditorCustomElementRenderer createInlayRenderer(Editor editor) {
            return new HintRenderer(MavenDetectiveBundle.message("console.show.cause"));
        }
    }

    /** Grauer, kursiver Text in der Schrift des Editors. */
    private static final class HintRenderer implements EditorCustomElementRenderer {
        private final String text;

        HintRenderer(String text) {
            this.text = "  " + text;
        }

        private static Font font(Editor editor) {
            return editor.getColorsScheme().getFont(EditorFontType.ITALIC);
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            Editor editor = inlay.getEditor();
            return editor.getContentComponent().getFontMetrics(font(editor)).stringWidth(text);
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle target, @NotNull TextAttributes attrs) {
            Editor editor = inlay.getEditor();
            TextAttributes hint = editor.getColorsScheme().getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT);
            Color color = hint == null || hint.getForegroundColor() == null ? JBColor.GRAY : hint.getForegroundColor();
            Font f = font(editor);
            g.setFont(f);
            g.setColor(color);
            g.drawString(text, target.x, target.y + editor.getAscent());
        }
    }
}
