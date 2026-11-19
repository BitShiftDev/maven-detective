package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceScannerTest {

    @Test
    void collectsImportsQualifiedNamesAndWildcards() {
        String java = """
                package com.acme;

                import org.web.Web;
                import static org.junit.Assert.assertEquals;
                import org.slf4j.*;
                // import org.commented.Out;
                /* org.block.Comment */

                public class App {
                    private final Logger log = LoggerFactory.getLogger(App.class);
                    String s = "org.in.String";
                    String t = \"""
                        org.in.TextBlock
                        \""";
                    char c = '"';
                    java.util.List<org.other.Thing> list;
                }
                """;
        ClassUsages u = new ClassUsages();
        SourceScanner.scanText(java, false, Paths.get("App.java"), u);
        Set<String> names = u.names(false);
        assertTrue(names.contains("org.web.Web"), names.toString());
        assertTrue(names.contains("org.junit.Assert.assertEquals"), names.toString());
        assertTrue(names.contains("org.other.Thing"), names.toString());
        assertTrue(names.contains("java.util.List"), names.toString());
        assertFalse(names.contains("org.commented.Out"));
        assertFalse(names.contains("org.block.Comment"));
        assertFalse(names.contains("org.in.String"));
        assertFalse(names.contains("org.in.TextBlock"));
        assertFalse(names.contains("com.acme"), "package zählt nicht");
        assertEquals(1, u.getMainFiles());

        List<ClassUsages.Wildcard> w = u.wildcards(false);
        assertEquals(1, w.size());
        assertEquals("org.slf4j", w.get(0).pkg);
        assertTrue(w.get(0).simpleNames.containsAll(List.of("Logger", "LoggerFactory", "App")));
        assertEquals(5, w.get(0).line);
    }

    @Test
    void lineNumbersSurviveComments() {
        String java = "/*\n * x\n */\npackage a;\n\nclass B {\n  org.x.Y y;\n}\n";
        ClassUsages u = new ClassUsages();
        SourceScanner.scanText(java, true, Paths.get("B.java"), u);
        ClassUsages.Use use = u.uses(true).iterator().next();
        assertEquals("org.x.Y", use.getClassName());
        assertEquals(7, use.getLine());
        assertEquals(1, u.getTestFiles());
    }

    @Test
    void kotlinImportsWithAlias() {
        String kt = "package a\n\nimport org.x.Y as Z\nimport org.x.W\n\nfun f(z: Z) = W()\n";
        ClassUsages u = new ClassUsages();
        SourceScanner.scanText(kt, false, Paths.get("F.kt"), u);
        assertTrue(u.names(false).containsAll(List.of("org.x.Y", "org.x.W")), u.names(false).toString());
    }
    /** Review #63: Escapes in Text-Blöcken, Groovy-Strings mit drei einfachen Anführungszeichen, Templates in Kotlin/Groovy. */
    @Test
    void stringsWithEscapesAndTemplates() {
        String java = "class A {\n  String t = \"\"\"\n    say \\\"\"\" org.fake.Inside\n    \"\"\";\n  org.real.Used u;\n}\n";
        ClassUsages u = new ClassUsages();
        SourceScanner.scanText(java, false, Paths.get("A.java"), u);
        assertTrue(u.names(false).contains("org.real.Used"), u.names(false).toString());
        assertFalse(u.names(false).contains("org.fake.Inside"), u.names(false).toString());

        String kt = "fun f() = \"value: ${org.x.T.f()} and {org.fake.Text}\"\nval raw = \"\"\"C:\\\"\"\"\nval y = org.real.Y()\n";
        u = new ClassUsages();
        SourceScanner.scanText(kt, false, Paths.get("F.kt"), u);
        assertTrue(u.names(false).containsAll(List.of("org.x.T", "org.real.Y")), u.names(false).toString());
        assertFalse(u.names(false).contains("org.fake.Text"), u.names(false).toString());

        String groovy = "def s = '''\norg.fake.Single\n'''\ndef g = \"${org.x.G.name}\"\norg.real.Z z\n";
        u = new ClassUsages();
        SourceScanner.scanText(groovy, false, Paths.get("S.groovy"), u);
        assertTrue(u.names(false).containsAll(List.of("org.x.G", "org.real.Z")), u.names(false).toString());
        assertFalse(u.names(false).contains("org.fake.Single"), u.names(false).toString());
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Modul mit Quellen in allen Sprachen und generierten Quellen (Testplan Welle 3). */
    @Test
    void modulMitKotlinGroovyUndGeneriertenQuellen(@TempDir Path module) throws IOException {
        write(module.resolve("src/main/java/a/A.java"), "package a;\nimport org.main.Java;\nclass A {}\n");
        write(module.resolve("src/main/kotlin/a/K.kt"), "package a\nimport org.main.Kotlin\n");
        write(module.resolve("src/main/groovy/a/G.groovy"), "package a\nimport org.main.Groovy\n");
        write(module.resolve("src/test/kotlin/a/KT.kt"), "package a\nimport org.test.Kotlin\n");
        write(module.resolve("src/test/groovy/a/GT.groovy"), "package a\nimport org.test.Groovy\n");
        write(module.resolve("target/generated-sources/annotations/a/Gen.java"), "package a;\nimport org.main.Generated;\n");
        write(module.resolve("target/generated-test-sources/test-annotations/a/GenT.java"), "package a;\nimport org.test.Generated;\n");
        write(module.resolve("src/main/resources/a/NotSource.txt"), "import org.never.Seen;\n");
        write(module.resolve("build.gradle.kts"), "import org.never.Script\n");
        ClassUsages u = SourceScanner.scan(module, Cancellation.NONE);
        assertEquals(Set.of("org.main.Java", "org.main.Kotlin", "org.main.Groovy", "org.main.Generated"), filter(u.names(false)));
        assertEquals(Set.of("org.test.Kotlin", "org.test.Groovy", "org.test.Generated"), filter(u.names(true)));
        assertEquals(4, u.getMainFiles());
        assertEquals(3, u.getTestFiles());
    }

    private static Set<String> filter(Set<String> names) {
        Set<String> out = new java.util.TreeSet<>();
        for (String n : names) {
            if (n.startsWith("org.")) {
                out.add(n);
            }
        }
        return out;
    }
}
