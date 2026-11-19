package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.testkit.TestRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Projekte für die CLI-Matrix und die Golden Files der Befehle, je mit eigenem lokalem Repository unter {@code repo/}:
 * <ul>
 *   <li>{@link State#NONE}: leerer Ordner,</li>
 *   <li>{@link State#BROKEN}: {@code pom.xml} ist kein XML,</li>
 *   <li>{@link State#CLEAN}: ein Modul, jede Dependency wird genutzt, nichts doppelt, keine Exclusion,</li>
 *   <li>{@link State#FINDING}: Parent mit zwei Modulen – Guava in zwei Versionen (A2), veraltete Exclusion (A4),
 *       {@code Log} aus commons-logging und jcl-over-slf4j (A3), slf4j-api nur transitiv und eine ungenutzte
 *       Bibliothek (A5).</li>
 * </ul>
 */
final class CliProjects {

    enum State { NONE, BROKEN, CLEAN, FINDING }

    static final String GUAVA = "com.google.guava:guava";

    private CliProjects() {
    }

    /** Legt das Projekt unter {@code dir} an (Repository: {@code dir/repo}) und gibt {@code dir} zurück. */
    static Path create(Path dir, State state) {
        try {
            Files.createDirectories(dir);
            repository(TestRepository.at(dir.resolve("repo")));
            switch (state) {
                case NONE:
                    break;
                case BROKEN:
                    write(dir.resolve("pom.xml"), "<project><groupId>com.acme</groupId><artifactId>app\n");
                    break;
                case CLEAN:
                    clean(dir);
                    break;
                default:
                    finding(dir);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void repository(TestRepository repo) {
        repo.pom("org.web", "web", "1.0", pom("org.web", "web", "1.0",
                dep("commons-logging", "commons-logging", "1.2") + dep("org.slf4j", "slf4j-api", "2.0")));
        repo.jar("org.web", "web", "1.0", null, "", "org.web.Web");
        repo.pom("commons-logging", "commons-logging", "1.2");
        repo.jar("commons-logging", "commons-logging", "1.2", null, "orig", "org.apache.commons.logging.Log");
        repo.pom("org.slf4j", "jcl-over-slf4j", "1.7.36");
        repo.jar("org.slf4j", "jcl-over-slf4j", "1.7.36", null, "bridge", "org.apache.commons.logging.Log");
        repo.pom("org.slf4j", "slf4j-api", "2.0");
        repo.jar("org.slf4j", "slf4j-api", "2.0", null, "", "org.slf4j.Logger");
        repo.pom("org.unused", "lib", "1.0");
        repo.jar("org.unused", "lib", "1.0", null, "", "org.unused.Lib");
        for (String v : new String[] {"32.1.3-jre", "33.3.1-jre"}) {
            repo.pom("com.google.guava", "guava", v);
            repo.jar("com.google.guava", "guava", v, null, v, "com.google.common.base.Strings");
        }
    }

    private static void clean(Path dir) throws IOException {
        write(dir.resolve("pom.xml"), "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.acme</groupId>\n  <artifactId>app</artifactId>\n  <version>1.0</version>\n"
                + "  <dependencies>\n"
                + dep("org.slf4j", "slf4j-api", "2.0")
                + dep("com.google.guava", "guava", "32.1.3-jre")
                + "  </dependencies>\n</project>\n");
        source(dir, "com/acme/App.java", "package com.acme;\nimport org.slf4j.Logger;\nimport com.google.common.base.Strings;\n"
                + "class App {}\n");
    }

    private static void finding(Path dir) throws IOException {
        write(dir.resolve("pom.xml"), "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.acme</groupId>\n  <artifactId>parent</artifactId>\n  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules>\n    <module>a</module>\n    <module>b</module>\n  </modules>\n</project>\n");
        write(dir.resolve("a/pom.xml"), "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent>\n    <groupId>com.acme</groupId>\n    <artifactId>parent</artifactId>\n    <version>1.0</version>\n"
                + "  </parent>\n"
                + "  <artifactId>a</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency>\n      <groupId>org.web</groupId>\n      <artifactId>web</artifactId>\n      <version>1.0</version>\n"
                + "      <exclusions>\n        <exclusion>\n          <groupId>org.gone</groupId>\n          <artifactId>gone</artifactId>\n"
                + "        </exclusion>\n      </exclusions>\n    </dependency>\n"
                + dep("org.slf4j", "jcl-over-slf4j", "1.7.36")
                + dep("com.google.guava", "guava", "33.3.1-jre")
                + dep("org.unused", "lib", "1.0")
                + "  </dependencies>\n</project>\n");
        write(dir.resolve("b/pom.xml"), "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent>\n    <groupId>com.acme</groupId>\n    <artifactId>parent</artifactId>\n    <version>1.0</version>\n"
                + "  </parent>\n"
                + "  <artifactId>b</artifactId>\n"
                + "  <dependencies>\n"
                + dep("com.google.guava", "guava", "32.1.3-jre")
                + "  </dependencies>\n</project>\n");
        source(dir.resolve("a"), "com/acme/a/A.java", "package com.acme.a;\nimport org.web.Web;\nimport org.slf4j.Logger;\n"
                + "import org.apache.commons.logging.Log;\nimport com.google.common.base.Strings;\nclass A {}\n");
        source(dir.resolve("b"), "com/acme/b/B.java", "package com.acme.b;\nimport com.google.common.base.Strings;\nclass B {}\n");
    }

    private static String pom(String g, String a, String v, String deps) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version><dependencies>" + deps + "</dependencies></project>";
    }

    private static String dep(String g, String a, String v) {
        return "    <dependency>\n      <groupId>" + g + "</groupId>\n      <artifactId>" + a + "</artifactId>\n"
                + "      <version>" + v + "</version>\n    </dependency>\n";
    }

    private static void source(Path module, String file, String text) throws IOException {
        write(module.resolve("src/main/java").resolve(file), text);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Ergebnis eines {@code Main.run}: Exit-Code, stdout, stderr. */
    static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        /** Erste Zeile von stdout, sonst von stderr. */
        String firstLine() {
            String text = out.isEmpty() ? err : out;
            int nl = text.indexOf('\n');
            return (nl < 0 ? text : text.substring(0, nl)).replace("\r", "");
        }

        @Override
        public String toString() {
            return "exit " + code + "\n--- out\n" + out + "--- err\n" + err;
        }
    }

    static Result run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
