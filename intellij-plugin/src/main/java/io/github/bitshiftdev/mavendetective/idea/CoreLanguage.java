package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.ide.AppLifecycleListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Hängt die Sprache des Kerns beim Start der IDE an die Einstellung des Plugins – bevor der erste
 * Maven-Lauf diagnostiziert wird, auch wenn das Tool-Window noch nie offen war.
 */
public final class CoreLanguage implements AppLifecycleListener {

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        MavenDetectiveBundle.connectCore();
    }
}
