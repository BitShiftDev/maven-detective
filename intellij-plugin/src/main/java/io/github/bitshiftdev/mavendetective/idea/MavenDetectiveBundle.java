package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.AbstractBundle;
import com.intellij.DynamicBundle;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import io.github.bitshiftdev.mavendetective.core.Texts;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;
import org.jetbrains.annotations.TestOnly;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Texte der Oberfläche: {@code messages/MavenDetectiveBundle.properties} (Englisch, Standard) und
 * {@code MavenDetectiveBundle_de.properties} (Deutsch).
 *
 * <p>Die Sprache wählt die Einstellung {@link DetectiveSettings#getLanguage()}: „Automatisch“ folgt
 * der Sprache der IDE, die das installierte Sprachpaket bestimmt. Ein deutsches Paket gibt es nicht,
 * also sähe ohne eigene Einstellung fast jeder Englisch. Deshalb lädt die Klasse das Bundle selbst
 * statt über {@link DynamicBundle}.
 *
 * <p>Der Kern ({@link Texts}) folgt derselben Einstellung ({@link #connectCore()}).
 *
 * <p>Was die Plattform selbst aus {@code plugin.xml} liest (Namen der Inspections, Inlay-Einstellungen,
 * Beschreibungen von Inspections und Intentions), folgt immer der Sprache der IDE. Texte registrierter
 * Aktionen setzt {@link #localize} beim {@code update} nach.
 */
public final class MavenDetectiveBundle {

    public static final String BUNDLE = "messages.MavenDetectiveBundle";

    /** Sprache der Oberfläche (Einstellung). */
    public enum Language {
        AUTO, ENGLISH, GERMAN
    }

    private static volatile ResourceBundle bundle;
    private static volatile Language forced;

    static {
        connectCore();
    }

    private MavenDetectiveBundle() {
    }

    /**
     * Texte des Kerns (Diagnosen, Befunde, Fix-Bezeichnungen) in derselben Sprache wie die Oberfläche.
     * Läuft beim Laden der Klasse und beim Start der IDE ({@link CoreLanguage}).
     */
    public static void connectCore() {
        Texts.setSource(() -> Locale.GERMAN.equals(bundleLocale()) ? "de" : "en");
    }

    public static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
        return AbstractBundle.message(bundle(), key, params);
    }

    /** Text oder {@code null}, wenn der Schlüssel fehlt. */
    public static @Nls @Nullable String messageOrNull(@NotNull String key, Object @NotNull ... params) {
        return AbstractBundle.messageOrNull(bundle(), key, params);
    }

    /**
     * Text und Beschreibung einer in {@code plugin.xml} registrierten Aktion aus diesem Bundle setzen.
     * Die Plattform liest sie in der Sprache der IDE; weicht die Einstellung davon ab, stimmt erst das
     * hier. Aufruf am Anfang von {@code update}; seitenabhängige Texte setzt die Aktion danach.
     */
    public static void localize(@NotNull AnAction action, @NotNull Presentation p) {
        if (bundleLocale().equals(ideLocale())) {
            return;
        }
        String id = ActionManager.getInstance().getId(action);
        if (id == null) {
            return;
        }
        String text = messageOrNull("action." + id + ".text");
        if (text != null) {
            p.setText(text);
        }
        String description = messageOrNull("action." + id + ".description");
        if (description != null) {
            p.setDescription(description);
        }
    }

    /** Sprache, in der die Texte gerade erscheinen. */
    public static @NotNull Locale bundleLocale() {
        return locale(configured());
    }

    /** Nach Änderung der Einstellung: neu gebaute Oberfläche nutzt die neue Sprache (offene Seiten erst nach Neustart). */
    public static void reload() {
        bundle = null;
    }

    @TestOnly
    public static void useForTests(@Nullable Language language) {
        forced = language;
        bundle = null;
    }

    static @NotNull Locale locale(@NotNull Language language) {
        return switch (language) {
            case ENGLISH -> Locale.ROOT;
            case GERMAN -> Locale.GERMAN;
            case AUTO -> ideLocale();
        };
    }

    private static ResourceBundle bundle() {
        ResourceBundle b = bundle;
        if (b == null) {
            b = load(bundleLocale());
            bundle = b;
        }
        return b;
    }

    static @NotNull ResourceBundle load(@NotNull Locale locale) {
        // Ohne Rückfall auf die Sprache der JVM: Locale.ROOT lädt nur die englische Standarddatei,
        // Deutsch fällt für fehlende Schlüssel auf sie zurück
        return ResourceBundle.getBundle(BUNDLE, locale, MavenDetectiveBundle.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static Language configured() {
        Language f = forced;
        if (f != null) {
            return f;
        }
        try {
            return ApplicationManager.getApplication() == null ? Language.AUTO : DetectiveSettings.getInstance().getLanguage();
        } catch (RuntimeException e) {
            return Language.AUTO;
        }
    }

    /** Sprache der IDE, auf die zwei vorhandenen abgebildet. */
    private static Locale ideLocale() {
        try {
            return "de".equals(DynamicBundle.getLocale().getLanguage()) ? Locale.GERMAN : Locale.ROOT;
        } catch (RuntimeException | LinkageError e) {
            return Locale.ROOT;
        }
    }
}
