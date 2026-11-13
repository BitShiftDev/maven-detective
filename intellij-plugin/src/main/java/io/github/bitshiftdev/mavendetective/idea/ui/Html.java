package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ui.ColorUtil;
import com.intellij.util.ui.NamedColorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Bausteine für die HTML-Detailtexte der Panels; Links zeigen auf {@code pfad:zeile} (siehe {@link Locations}). */
public final class Html {

    private Html() {
    }

    public static @NotNull String esc(@Nullable String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static @NotNull String page(@NotNull String body) {
        return "<html><body>" + body + "</body></html>";
    }

    /** Link mit beliebigem Ziel; Ziel und Text werden maskiert. */
    public static @NotNull String link(@NotNull String target, @NotNull String label) {
        return "<a href=\"" + esc(target) + "\">" + esc(label) + "</a>";
    }

    /** Link auf Datei und Zeile, beschriftet mit {@code datei.xml:zeile}. */
    public static @NotNull String fileLink(@NotNull String file, int line) {
        Path name = Paths.get(file).getFileName();
        return link(Locations.of(file, line), (name == null ? file : name.toString()) + (line > 0 ? ":" + line : ""));
    }

    /** Wie {@link #fileLink}, aber mit Verzeichnis davor ({@code modul/pom.xml:12}) – bei vielen gleichnamigen POMs. */
    public static @NotNull String pomLink(@NotNull String file, int line) {
        Path p = Paths.get(file);
        Path dir = p.getParent() == null ? null : p.getParent().getFileName();
        String label = (dir == null ? "" : dir + "/") + p.getFileName() + (line > 0 ? ":" + line : "");
        return link(Locations.of(file, line), label);
    }

    /** Bereits maskiertes HTML in der Fehlerfarbe des Themes. */
    public static @NotNull String error(@NotNull String html) {
        return "<span style=\"color:#" + ColorUtil.toHex(NamedColorUtil.getErrorForeground()) + "\">" + html + "</span>";
    }
}
