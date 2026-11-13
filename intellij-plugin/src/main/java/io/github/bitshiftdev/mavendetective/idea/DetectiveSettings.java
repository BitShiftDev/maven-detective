package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Einstellungen des Plugins (<i>Settings | Tools | Maven Detective</i>, {@link DetectiveConfigurable}),
 * für alle Projekte gleich. Inlay-Hinweise und Code Vision stellt die IDE selbst ein
 * (<i>Editor | Inlay Hints</i>), die Inspections ihre Profile – die Seite verlinkt dorthin.
 */
@Service(Service.Level.APP)
@State(name = "MavenDetectiveSettings", storages = @Storage("mavenDetective.xml"))
public final class DetectiveSettings implements PersistentStateComponent<DetectiveSettings.State> {

    /** Gespeicherter Zustand; öffentliche Felder, damit die Plattform ihn als XML ablegt. */
    public static final class State {
        /** Sprechblase bei rotem Build, wenn das Tool-Window nicht zu sehen ist (das Badge kommt immer). */
        public boolean notifyRedBuild = true;
        /** Diff-Vorschau vor jeder POM-Änderung. */
        public boolean previewPomFixes = true;
        /** Nach einem Quick-Fix denselben Lauf neu starten (Fix-und-Prüf-Schleife). */
        public boolean rebuildAfterFix = true;
        /** Schnappschüsse für „Was hat sich geändert?“ ablegen und vergleichen. */
        public boolean snapshots = true;
        /** Basisordner wie {@code -DmavenDetective.home} (Schnappschüsse in {@code snapshots/}); leer = wie die Maven-Extension. */
        public String snapshotDir = "";
        /** Seite „Ungenutzt/undeklariert“: auch Klassen zeigen, die nur über einen Starter kommen. */
        public boolean usageViaStarters;
        /** Sprache der Oberfläche ({@link MavenDetectiveBundle.Language}); AUTO = wie die IDE. */
        public String language = MavenDetectiveBundle.Language.AUTO.name();
    }

    private State state = new State();

    public static DetectiveSettings getInstance() {
        return ApplicationManager.getApplication().getService(DetectiveSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state = loaded;
    }

    public boolean isNotifyRedBuild() {
        return state.notifyRedBuild;
    }

    public void setNotifyRedBuild(boolean v) {
        state.notifyRedBuild = v;
    }

    public boolean isPreviewPomFixes() {
        return state.previewPomFixes;
    }

    public void setPreviewPomFixes(boolean v) {
        state.previewPomFixes = v;
    }

    public boolean isRebuildAfterFix() {
        return state.rebuildAfterFix;
    }

    public void setRebuildAfterFix(boolean v) {
        state.rebuildAfterFix = v;
    }

    public boolean isSnapshots() {
        return state.snapshots;
    }

    public void setSnapshots(boolean v) {
        state.snapshots = v;
    }

    public @NotNull String getSnapshotDir() {
        return state.snapshotDir == null ? "" : state.snapshotDir.trim();
    }

    public void setSnapshotDir(@Nullable String v) {
        state.snapshotDir = v == null ? "" : v.trim();
    }

    public boolean isUsageViaStarters() {
        return state.usageViaStarters;
    }

    public void setUsageViaStarters(boolean v) {
        state.usageViaStarters = v;
    }

    public @NotNull MavenDetectiveBundle.Language getLanguage() {
        try {
            return state.language == null ? MavenDetectiveBundle.Language.AUTO : MavenDetectiveBundle.Language.valueOf(state.language);
        } catch (IllegalArgumentException e) {
            return MavenDetectiveBundle.Language.AUTO;
        }
    }

    public void setLanguage(@NotNull MavenDetectiveBundle.Language v) {
        state.language = v.name();
    }

    /**
     * Ablage der Schnappschüsse ({@code null}: abgeschaltet). Ohne eigenen Ort derselbe wie die
     * Maven-Extension (inkl. {@code -DmavenDetective.home} / {@code MAVEN_DETECTIVE_HOME}), damit IDE und
     * Kommandozeile dieselbe grüne Referenz sehen.
     */
    public @Nullable SnapshotStore snapshotStore() {
        return snapshotStore(state.snapshots, getSnapshotDir());
    }

    static @Nullable SnapshotStore snapshotStore(boolean enabled, @NotNull String dir) {
        if (!enabled) {
            return null;
        }
        Path own = parseDir(dir);
        return own == null ? SnapshotStore.defaultStore() : new SnapshotStore(own.resolve("snapshots"));
    }

    /** Eingetragener Ordner als Pfad ({@code null}: leer oder kein gültiger Pfad). */
    static @Nullable Path parseDir(@Nullable String dir) {
        if (dir == null || dir.isBlank()) {
            return null;
        }
        try {
            String d = dir.trim();
            if (d.equals("~") || d.startsWith("~/")) {
                d = System.getProperty("user.home") + d.substring(1);
            }
            Path p = Paths.get(d);
            return p.isAbsolute() ? p : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
