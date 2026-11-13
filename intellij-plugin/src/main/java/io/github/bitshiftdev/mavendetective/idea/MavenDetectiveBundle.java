package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * Texte der Oberfläche ({@code messages/MavenDetectiveBundle.properties}). Bisher stehen hier die
 * Texte von Tabs, Aktionen, Knöpfen, Filtern und leeren Zuständen; die HTML-Detailtexte der Panels
 * folgen, sobald die Sprache der Oberfläche entschieden ist (UI-Plan, offene Fragen).
 */
public final class MavenDetectiveBundle extends DynamicBundle {

    public static final String BUNDLE = "messages.MavenDetectiveBundle";
    private static final MavenDetectiveBundle INSTANCE = new MavenDetectiveBundle();

    private MavenDetectiveBundle() {
        super(MavenDetectiveBundle.class, BUNDLE);
    }

    public static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
