package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.Test;

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
}
