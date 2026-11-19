package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.testFramework.PlatformTestUtil;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.ClassUsages;
import io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api;
import io.github.bitshiftdev.mavendetective.idea.testlib.transitive.Helper;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Future;

/** A5 in der IDE: {@link IdeUsages#collect} löst Java über die PSI auf, Kotlin/Groovy über den Text-Scanner. */
public class IdeUsagesTest extends PomFixtureTestCase {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example.app</groupId>
              <artifactId>app</artifactId>
              <version>1</version>
            </project>
            """;

    private static final Cancellation NONE = () -> { };

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        repoJarAsLibrary("org.example", "direct", "1.0", Api.class);
        repoJarAsLibrary("org.example", "transitive", "1.0", Helper.class);
        writePom(POM);
    }

    private ClassUsages collect() throws Exception {
        return collect(root.resolve("pom.xml"), NONE);
    }

    /** Wie im Tab: aus einem Hintergrund-Thread (auf dem EDT ließe sich „Smart Mode“ nicht abwarten). */
    private ClassUsages collect(Path pom, Cancellation cancel) throws Exception {
        // Die Plattform schluckt ProcessCanceledException in Pool-Threads: Ausnahme als Ergebnis zurückgeben
        Future<Object> f = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                return IdeUsages.collect(getProject(), pom, cancel);
            } catch (RuntimeException e) {
                return e;
            }
        });
        // pumpt die Event-Queue: die Read-Action im Smart Mode wird über den EDT eingeplant
        Object result = PlatformTestUtil.waitForFuture(f, 60_000);
        if (result instanceof RuntimeException e) {
            throw e;
        }
        return (ClassUsages) result;
    }

    public void testJavaReferenzenWerdenAufgeloestNichtGeraten() throws Exception {
        write(root.resolve("org/example/app/App.java"), """
                package org.example.app;

                import java.util.List;

                public class App {
                    // Helper steht nur im Kommentar
                    io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api api;
                    List<String> names;
                    String text = "Helper";
                    Inner inner;
                    static class Inner { }
                }
                """);
        ClassUsages u = collect();
        Set<String> main = u.names(false);
        assertTrue(main.toString(), main.contains(Api.class.getName()));
        assertFalse("Kommentar und Text nennen keine Klasse", main.contains(Helper.class.getName()));
        assertFalse("JDK zählt nicht", main.contains("java.util.List"));
        assertFalse("innere Klasse zählt als äußere", main.contains("org.example.app.App.Inner"));
        assertEquals(1, u.getMainFiles());
    }

    public void testStatischerImportUndQualifizierterAufrufNennenDieKlasse() throws Exception {
        write(root.resolve("org/example/app/Uses.java"), """
                package org.example.app;

                import static io.github.bitshiftdev.mavendetective.idea.testlib.transitive.Helper.*;

                public class Uses {
                    Object o = io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api.class;
                }
                """);
        Set<String> main = collect().names(false);
        assertTrue(main.toString(), main.contains(Helper.class.getName()));
        assertTrue(main.toString(), main.contains(Api.class.getName()));
    }

    public void testFundstelleTraegtDateiUndZeile() throws Exception {
        Path file = root.resolve("org/example/app/Line.java");
        write(file, """
                package org.example.app;


                class Line {
                    io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api api;
                }
                """);
        ClassUsages.Use use = collect().find(Api.class.getName(), false);
        assertNotNull(use);
        assertEquals(file, use.getFile());
        assertEquals(5, use.getLine());
    }

    public void testKotlinUndGroovyLaufenUeberDenTextScanner() throws Exception {
        write(root.resolve("org/example/app/Tool.kt"), """
                package org.example.app

                import io.github.bitshiftdev.mavendetective.idea.testlib.transitive.Helper

                class Tool(val h: Helper)
                """);
        write(root.resolve("org/example/app/Script.groovy"), """
                package org.example.app

                import io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api

                class Script { Api api }
                """);
        Set<String> main = collect().names(false);
        assertTrue(main.toString(), main.contains(Helper.class.getName()));
        assertTrue(main.toString(), main.contains(Api.class.getName()));
    }

    public void testNichtImportiertesModulLiestDenTextScanner() throws Exception {
        Path other = root.resolve("other");
        write(other.resolve("pom.xml"), POM.replace("<artifactId>app</artifactId>", "<artifactId>other</artifactId>"));
        write(other.resolve("src/main/java/org/example/other/O.java"), """
                package org.example.other;

                import io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api;

                class O { Api api; }
                """);
        ClassUsages u = collect(other.resolve("pom.xml"), NONE);
        assertEquals(Set.of(Api.class.getName()), u.names(false));
    }

    public void testAbbruchBeendetDieSammlung() throws Exception {
        write(root.resolve("org/example/app/App.java"), "package org.example.app; class App { }");
        try {
            collect(root.resolve("pom.xml"), () -> {
                throw new ProcessCanceledException();
            });
            fail("Abbruch erwartet");
        } catch (ProcessCanceledException expected) {
            // gewollt
        }
    }
}
