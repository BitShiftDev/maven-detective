package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Abbruch-Hook der IDE: Er muss durch alle Lesewege durchgehen und darf nie geschluckt werden. */
class PomLocatorTest {

    @TempDir
    Path tmp;

    /** Steht für {@code ProcessCanceledException} der IDE. */
    static final class Cancelled extends RuntimeException {
    }

    static final Cancellation ALWAYS = () -> {
        throw new Cancelled();
    };

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ohneAbbruchWirdGelesen() throws IOException {
        Path pom = tmp.resolve("pom.xml");
        write(pom, "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>");
        assertNotNull(new PomLocator(tmp, tmp.resolve("repo"), new PomCache(), Cancellation.NONE).load(pom));
    }

    @Test
    void abbruchGehtDurchLoadMitUndOhneGemeinsamenCache() throws IOException {
        Path pom = tmp.resolve("pom.xml");
        write(pom, "<project><kaputt");   // Lesefehler würden intern gefangen – der Abbruch nicht
        assertThrows(Cancelled.class, () -> new PomLocator(tmp, tmp.resolve("repo"), new PomCache(), ALWAYS).load(pom));
        assertThrows(Cancelled.class, () -> new PomLocator(tmp, tmp.resolve("repo"), null, ALWAYS).load(pom));
    }

    @Test
    void abbruchGehtDurchReactorSuche() throws IOException {
        write(tmp.resolve("modul/pom.xml"), "<project><groupId>g</groupId><artifactId>m</artifactId><version>1</version></project>");
        // Nur der Reactor-Scan (ProjectPoms) liest hier – load() wird gar nicht erreicht
        Cancellation nurScan = () -> {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                if (e.getMethodName().equals("scan")) {
                    throw new Cancelled();
                }
            }
        };
        PomLocator locator = new PomLocator(tmp, tmp.resolve("repo"), null, nurScan);
        assertThrows(Cancelled.class, () -> locator.byCoordinates("g", "m", "1"));
    }

    @Test
    void cacheTrefferBrauchtKeinePruefung() throws IOException {
        Path pom = tmp.resolve("pom.xml");
        write(pom, "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>");
        boolean[] cancel = {false};
        PomLocator locator = new PomLocator(tmp, tmp.resolve("repo"), null, () -> {
            if (cancel[0]) {
                throw new Cancelled();
            }
        });
        assertNotNull(locator.load(pom));
        cancel[0] = true;
        assertNotNull(locator.load(pom));
        assertNull(new PomLocator(tmp, tmp.resolve("repo")).load(tmp.resolve("fehlt.xml")));
    }
}
