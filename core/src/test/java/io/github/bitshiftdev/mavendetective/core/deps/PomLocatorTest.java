package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import java.util.Map;

/**
 * {@link PomLocator}: Der Abbruch-Hook der IDE muss durch alle Lesewege durchgehen und darf nie geschluckt werden;
 * {@link PomLocator#withOverrides} liest geänderte Texte statt der Platte, ohne den Ausgangs-Locator zu verändern.
 */
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

    // ---- withOverrides (Upgrade-Simulation im Speicher)

    Path project() throws IOException {
        write(tmp.resolve("p/pom.xml"), TestPom.of("org.app", "root", "1").packaging("pom").module("app")
                .property("lib.version", "1.0").managed("org.lib", "lib", "${lib.version}").xml());
        write(tmp.resolve("p/app/pom.xml"), TestPom.of(null, "app", null).parent("org.app", "root", "1")
                .dependency("org.lib", "lib", null).xml());
        return tmp.resolve("p");
    }

    @Test
    void overrideGiltFuerDieDateiUndAlleModelleDieSieBenutzen() throws IOException {
        Path root = project();
        PomLocator base = new PomLocator(root, tmp.resolve("repo"));
        PomFile app = base.load(root.resolve("app/pom.xml"));
        assertEquals("1.0", base.model(app).managed("org.lib", "lib").version().value);

        PomLocator changed = base.withOverrides(Map.of(root.resolve("pom.xml"), TestPom.of("org.app", "root", "1")
                .packaging("pom").module("app").property("lib.version", "2.0").managed("org.lib", "lib", "${lib.version}").xml()));
        PomFile appChanged = changed.load(root.resolve("app/pom.xml"));
        assertSame(app, appChanged, "unveränderte POMs werden übernommen");
        assertEquals("2.0", changed.model(appChanged).managed("org.lib", "lib").version().value, "Modell neu gebaut");
        assertEquals("1.0", base.model(app).managed("org.lib", "lib").version().value, "Ausgangs-Locator unverändert");
        assertEquals("2.0", changed.parentOf(appChanged).getProperties().get("lib.version").getValue());
    }

    @Test
    void overrideMitRelativemOderUnnormalisiertemPfad() throws IOException {
        Path root = project();
        PomLocator base = new PomLocator(root, tmp.resolve("repo"));
        PomLocator changed = base.withOverrides(Map.of(root.resolve("app/../pom.xml"),
                TestPom.of("org.app", "root", "1").packaging("pom").property("lib.version", "3.0")
                        .managed("org.lib", "lib", "${lib.version}").xml()));
        PomFile app = changed.load(root.resolve("app/pom.xml"));
        assertEquals("3.0", changed.model(app).managed("org.lib", "lib").version().value);
    }

    @Test
    void overridesSammelnSichUeberMehrereSchritte() throws IOException {
        Path root = project();
        PomLocator first = new PomLocator(root, tmp.resolve("repo")).withOverrides(Map.of(root.resolve("pom.xml"),
                TestPom.of("org.app", "root", "1").packaging("pom").property("lib.version", "2.0")
                        .managed("org.lib", "lib", "${lib.version}").xml()));
        PomLocator second = first.withOverrides(Map.of(root.resolve("app/pom.xml"), TestPom.of(null, "app", null)
                .parent("org.app", "root", "1").dependency("org.lib", "lib", null).dependency("org.x", "x", "9").xml()));
        PomFile app = second.load(root.resolve("app/pom.xml"));
        assertEquals(2, app.getDependencies().size());
        assertEquals("2.0", second.model(app).managed("org.lib", "lib").version().value, "erster Override bleibt");
        assertNotSame(app, first.load(root.resolve("app/pom.xml")));
    }

    @Test
    void kaputterOverrideWirdAbgelehnt() throws IOException {
        Path root = project();
        PomLocator base = new PomLocator(root, tmp.resolve("repo"));
        assertThrows(IllegalArgumentException.class, () -> base.withOverrides(Map.of(root.resolve("pom.xml"), "<project><oops")));
    }
}
