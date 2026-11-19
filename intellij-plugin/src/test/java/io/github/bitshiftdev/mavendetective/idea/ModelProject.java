package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Kleiner Reactor samt lokalem Repository für die Modelltests der Seiten (ohne IDE): Die Ergebnisse des Kerns
 * (Matrix, Exclusions, doppelte Klassen, Nutzung) entstehen nur über dessen {@code build}, nie per Konstruktor.
 */
final class ModelProject {

    final Path root;
    final Path repo;
    private final List<Path> modules = new ArrayList<>();

    ModelProject(Path tmp) throws IOException {
        root = Files.createDirectories(tmp.resolve("projekt"));
        repo = Files.createDirectories(tmp.resolve("repo"));
    }

    /** Parent {@code org.example:parent:1} (packaging pom) mit den Modulen und optional eigenen Dependencies. */
    ModelProject parent(String dependencies, String... moduleNames) throws IOException {
        StringBuilder m = new StringBuilder();
        for (String name : moduleNames) {
            m.append("    <module>").append(name).append("</module>\n");
        }
        write(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                  <modules>
                %s  </modules>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(m, dependencies));
        modules.add(root.resolve("pom.xml"));
        return this;
    }

    /** Modul {@code org.example:name:1} mit Parent und diesen {@code <dependency>}-Einträgen. */
    ModelProject module(String name, String dependencies) throws IOException {
        Path pom = root.resolve(name).resolve("pom.xml");
        write(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(name, dependencies));
        modules.add(pom);
        return this;
    }

    /** {@code <dependency>} mit {@code g:a:v}, optional mit Exclusions {@code eg:ea}. */
    static String dep(String gav, String... exclusions) {
        String[] p = gav.split(":");
        StringBuilder x = new StringBuilder();
        if (exclusions.length > 0) {
            x.append("      <exclusions>\n");
            for (String e : exclusions) {
                String[] q = e.split(":");
                x.append("        <exclusion><groupId>").append(q[0]).append("</groupId><artifactId>").append(q[1])
                        .append("</artifactId></exclusion>\n");
            }
            x.append("      </exclusions>\n");
        }
        return "    <dependency>\n      <groupId>" + p[0] + "</groupId>\n      <artifactId>" + p[1] + "</artifactId>\n"
                + (p.length > 2 ? "      <version>" + p[2] + "</version>\n" : "") + x + "    </dependency>\n";
    }

    /** POM {@code g:a:v} im Repository mit Dependencies {@code g:a:v}. */
    ModelProject repoPom(String gav, String... dependencies) throws IOException {
        String[] p = gav.split(":");
        StringBuilder deps = new StringBuilder();
        for (String d : dependencies) {
            deps.append(dep(d));
        }
        write(dir(p).resolve(p[1] + "-" + p[2] + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(p[0], p[1], p[2], deps));
        return this;
    }

    /** JAR {@code g:a:v} mit Einträgen {@code name=inhalt} (Klassenname ohne {@code .class}); der Inhalt bestimmt die CRC. */
    ModelProject repoJar(String gav, String... classes) throws IOException {
        String[] p = gav.split(":");
        Path jar = dir(p).resolve(p[1] + "-" + p[2] + ".jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String c : classes) {
                String[] kv = c.split("=", 2);
                zip.putNextEntry(new ZipEntry(kv[0].replace('.', '/') + ".class"));
                zip.write((kv.length > 1 ? kv[1] : kv[0]).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return this;
    }

    PomLocator locator() {
        return new PomLocator(root, repo);
    }

    /** Alle POMs des Reactors (Parent zuerst) – wie {@code DuplicateClassesPanel.projectPoms}. */
    List<PomFile> poms(PomLocator locator) {
        List<PomFile> out = new ArrayList<>();
        for (Path p : modules) {
            out.add(locator.load(p));
        }
        return out;
    }

    Path pom(String module) {
        return module.isEmpty() ? root.resolve("pom.xml") : root.resolve(module).resolve("pom.xml");
    }

    private Path dir(String[] gav) {
        return repo.resolve(gav[0].replace('.', '/')).resolve(gav[1]).resolve(gav[2]);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
