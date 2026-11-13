package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.GotItTooltip;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.nio.file.Path;

/**
 * Erste Schritte (UI-Plan U6): je ein „Got it“-Hinweis beim ersten roten Build (an der Ursache auf der
 * Karte) und beim ersten Öffnen einer {@code pom.xml} eines Maven-Projekts (Alt+Enter → „Warum diese
 * Version?“). Die Plattform merkt sich, dass ein Hinweis gesehen wurde; {@link #reset()} zeigt sie
 * wieder (Einstellungsseite).
 */
final class FirstSteps {

    static final String RED_BUILD = "io.github.bitshiftdev.mavendetective.firstRedBuild";
    static final String POM = "io.github.bitshiftdev.mavendetective.firstPom";
    /** So legt {@link GotItTooltip} ab, wie oft ein Hinweis gezeigt wurde. */
    private static final String PROPERTY_PREFIX = GotItTooltip.PROPERTY_PREFIX + ".";

    private FirstSteps() {
    }

    /** Hinweis an der Ursache der Karte, sobald sie zu sehen ist (nur einmal je Installation). */
    static void redBuild(@NotNull JComponent cause, @NotNull Disposable parent) {
        GotItTooltip tip = new GotItTooltip(RED_BUILD, MavenDetectiveBundle.message("tips.red.text"), parent)
                .withHeader(MavenDetectiveBundle.message("tips.red.header"));
        if (tip.canShow()) {
            whenShown(cause, () -> tip.show(cause, GotItTooltip.TOP_MIDDLE));
        }
    }

    /** {@code action} sofort, wenn {@code c} zu sehen ist, sonst einmal, sobald es sichtbar wird (EDT). */
    static void whenShown(@NotNull JComponent c, @NotNull Runnable action) {
        if (c.isShowing()) {
            action.run();
            return;
        }
        c.addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && c.isShowing()) {
                    c.removeHierarchyListener(this);
                    action.run();
                }
            }
        });
    }

    /** Alle Hinweise beim nächsten Anlass wieder zeigen. */
    static void reset() {
        PropertiesComponent props = PropertiesComponent.getInstance();
        props.unsetValue(PROPERTY_PREFIX + RED_BUILD);
        props.unsetValue(PROPERTY_PREFIX + POM);
    }

    /**
     * Erste {@code <dependency>} der Projekt-Dependencies (Offset des Tags), sonst -1. Grob über den
     * Text, nicht über PSI: {@code <dependencies>} in dependencyManagement, Plugins und Profilen zählen
     * nicht – dort hilft Alt+Enter nicht weiter.
     */
    static int firstDependency(@NotNull CharSequence text) {
        String s = text.toString();
        for (int deps = s.indexOf("<dependencies>"); deps >= 0; deps = s.indexOf("<dependencies>", deps + 1)) {
            String before = s.substring(0, deps);
            if (open(before, "dependencyManagement") || open(before, "plugin") || open(before, "profile")) {
                continue;
            }
            int end = s.indexOf("</dependencies>", deps);
            int dep = s.indexOf("<dependency>", deps);
            return dep >= 0 && (end < 0 || dep < end) ? dep : -1;
        }
        return -1;
    }

    /** Steht das Ende von {@code before} innerhalb eines offenen {@code <tag>}? */
    private static boolean open(String before, String tag) {
        return count(before, "<" + tag + ">") > count(before, "</" + tag + ">");
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    /** Erstes Öffnen einer {@code pom.xml}: Hinweis an der ersten Dependency (bzw. oben im sichtbaren Bereich). */
    public static final class PomOpened implements FileEditorManagerListener {

        private final Project project;

        public PomOpened(@NotNull Project project) {
            this.project = project;
        }

        @Override
        public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
            if (!"pom.xml".equals(file.getName()) || !PropertiesComponent.getInstance().getValue(PROPERTY_PREFIX + POM, "").isEmpty()) {
                return;
            }
            TextEditor textEditor = textEditor(source, file);
            Editor editor = textEditor == null ? null : textEditor.getEditor();
            if (editor == null || IdeMaven.module(project, Path.of(file.getPath())) == null) {
                return;
            }
            int offset = firstDependency(editor.getDocument().getCharsSequence());
            if (offset < 0) {
                return;
            }
            String shortcut = KeymapUtil.getFirstKeyboardShortcutText("ShowIntentionActions");
            GotItTooltip tip = new GotItTooltip(POM, MavenDetectiveBundle.message("tips.pom.text",
                    shortcut.isEmpty() ? "Alt+Enter" : shortcut), textEditor)
                    .withHeader(MavenDetectiveBundle.message("tips.pom.header"));
            if (!tip.canShow()) {
                return;
            }
            JComponent content = editor.getContentComponent();
            whenShown(content, () -> ApplicationManager.getApplication().invokeLater(() -> {
                if (!editor.isDisposed()) {
                    tip.show(content, (c, balloon) -> anchor(editor, offset));
                }
            }, project.getDisposed()));
        }

        private static @Nullable TextEditor textEditor(FileEditorManager source, VirtualFile file) {
            for (FileEditor fe : source.getEditors(file)) {
                if (fe instanceof TextEditor te) {
                    return te;
                }
            }
            return null;
        }

        /** Unter dem {@code <dependency>}-Tag, falls sichtbar; sonst oben links im sichtbaren Bereich. */
        private static Point anchor(Editor editor, int offset) {
            Point p = editor.offsetToXY(offset);
            Rectangle visible = editor.getScrollingModel().getVisibleArea();
            int line = editor.getLineHeight();
            if (visible.contains(p)) {
                return new Point(p.x + line, p.y + line);
            }
            return new Point(visible.x + 4 * line, visible.y + line);
        }
    }
}
