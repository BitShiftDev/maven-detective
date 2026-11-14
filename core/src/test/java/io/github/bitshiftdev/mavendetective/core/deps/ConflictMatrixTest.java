package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix.Module;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix.Row;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Source;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.fix.FixExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reactor mit drei Modulen unter einem Parent: {@code a} gibt lib 1.0 direkt an, {@code b} bekommt
 * lib 2.0 transitiv, {@code c} verwaltet lib 1.5 selbst (ohne Baum, nur aus der POM).
 */
class ConflictMatrixTest {

    @TempDir
    Path tmp;
    Path project;

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    static String module(String name, String body) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                %s
                </project>
                """.formatted(name, body);
    }

    @BeforeEach
    void setUp() throws IOException {
        project = tmp.resolve("project");
        write(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>a</module><module>b</module><module>c</module></modules>
                </project>
                """);
        write(project.resolve("a/pom.xml"), module("a", """
                  <dependencies>
                    <dependency>
                      <groupId>org.lib</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                """));
        write(project.resolve("b/pom.xml"), module("b", """
                  <dependencies>
                    <dependency>
                      <groupId>org.x</groupId>
                      <artifactId>x</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                """));
        write(project.resolve("c/pom.xml"), module("c", """
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib</artifactId>
                        <version>1.5</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.lib</groupId>
                      <artifactId>lib</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.x</groupId>
                      <artifactId>x</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                """));
    }

    ConflictMatrix build(boolean withTrees) throws IOException {
        PomLocator locator = new PomLocator(project, tmp.resolve("repo"));
        List<PomFile> poms = new ArrayList<>();
        for (ProjectPoms.Pom p : new ProjectPoms(project).all()) {
            poms.add(locator.load(p.file));
        }
        Map<String, DepNode> trees = new HashMap<>();
        if (withTrees) {
            String text = """
                    [INFO] com.acme:a:jar:1.0
                    [INFO] \\- org.lib:lib:jar:1.0:compile
                    [INFO] com.acme:b:jar:1.0
                    [INFO] \\- org.x:x:jar:1.0:compile
                    [INFO]    +- org.lib:lib:jar:2.0:compile
                    [INFO]    \\- org.lib:lib-extra:jar:3.0:test
                    """;
            for (DepNode n : DependencyTreeParser.parse(new StringReader(text))) {
                trees.put(n.ga(), n);
            }
        }
        return ConflictMatrix.build(locator, project, poms, trees);
    }

    static Row row(ConflictMatrix m, String ga) {
        for (Row r : m.getRows()) {
            if (r.ga().equals(ga)) {
                return r;
            }
        }
        return null;
    }

    static Module module(ConflictMatrix m, String name) {
        for (Module x : m.getModules()) {
            if (x.getName().equals(name)) {
                return x;
            }
        }
        return null;
    }

    @Test
    void showsVersionsPerModuleWithSource() throws IOException {
        ConflictMatrix m = build(true);
        assertEquals(List.of("a", "b", "c"), m.getModules().stream().map(Module::getName).toList(), "Parent ohne Dependencies fehlt");
        assertTrue(m.isPartial(), "c hat keinen Baum");
        assertFalse(module(m, "c").isFromTree());

        Row lib = row(m, "org.lib:lib");
        assertNotNull(lib);
        assertTrue(lib.isDivergent());
        assertEquals(List.of("1.0", "1.5", "2.0"), lib.versions());
        assertEquals(Source.DIRECT, lib.cell(module(m, "a")).getSource());
        assertEquals(project.resolve("a/pom.xml").toString(), lib.cell(module(m, "a")).getFile());
        assertEquals(13, lib.cell(module(m, "a")).getLine());
        assertEquals(Source.TRANSITIVE, lib.cell(module(m, "b")).getSource());
        assertEquals(2, lib.cell(module(m, "b")).getDepth());
        assertEquals(Source.MANAGED, lib.cell(module(m, "c")).getSource());

        assertFalse(row(m, "org.x:x").isDivergent());
        assertEquals("test", row(m, "org.lib:lib-extra").cell(module(m, "b")).getScope());
        assertEquals(1, m.divergent().size());
    }

    @Test
    void suggestsPinInCommonParentAndNamesBlockers() throws IOException {
        Row lib = row(build(true), "org.lib:lib");
        ConflictMatrix.Pin pin = lib.getPin();
        assertNotNull(pin, lib.getPinProblem());
        assertEquals(project.resolve("pom.xml"), pin.getTarget().getFile());
        assertEquals("2.0", pin.getVersion());
        assertNull(pin.getExisting());
        assertEquals(2, pin.getBlockers().size());
        assertTrue(pin.getBlockers().get(0).getText().startsWith("Direkte Angabe <version>1.0</version> in a"), pin.getBlockers().get(0).getText());
        assertTrue(pin.getBlockers().get(1).getText().startsWith("dependencyManagement in c (1.5)"), pin.getBlockers().get(1).getText());

        FixAction fix = pin.fix("org.lib", "lib");
        assertEquals(FixAction.PIN_VERSION, fix.getType());
        assertEquals("2.0", fix.param("version"));
    }

    @Test
    void pinFixWritesParentAndMatrixSeesIt() throws IOException {
        Row lib = row(build(true), "org.lib:lib");
        FixExecutor.Outcome o = new FixExecutor(List.of()).execute(lib.getPin().fix("org.lib", "lib"));
        assertTrue(o.actions.get(0).contains("dependencyManagement"), o.actions.toString());
        String parent = Files.readString(project.resolve("pom.xml"));
        assertTrue(parent.contains("""
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib</artifactId>
                        <version>2.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>"""), parent);

        // Zweiter Pin auf eine andere Version ändert den Eintrag statt einen zweiten anzulegen
        ConflictMatrix again = build(true);
        Row lib2 = row(again, "org.lib:lib");
        assertEquals("2.0", lib2.getPin().getExisting());
        new FixExecutor(List.of()).execute(lib2.getPin().fix("org.lib", "lib", "1.5"));
        String after = Files.readString(project.resolve("pom.xml"));
        assertEquals(1, after.split("<artifactId>lib</artifactId>", -1).length - 1, after);
        assertTrue(after.contains("<version>1.5</version>"), after);
    }

    @Test
    void withoutCommonParentThereIsNoPin() throws IOException {
        write(project.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                </project>
                """);
        write(project.resolve("b/pom.xml"), """
                <project>
                  <groupId>com.other</groupId><artifactId>b</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId><version>2.0</version></dependency>
                  </dependencies>
                </project>
                """);
        Row lib = row(build(false), "org.lib:lib");
        assertTrue(lib.isDivergent());
        assertNull(lib.getPin());
        assertTrue(lib.getPinProblem().contains("keinen gemeinsamen Parent"), lib.getPinProblem());
    }

    @Test
    void textAndMarkdown() throws IOException {
        ConflictMatrix m = build(true);
        String text = String.join("\n", m.lines(false));
        assertTrue(text.startsWith("Konflikt-Matrix: 3 Module, 3 Artifacts, 1 mit abweichenden Versionen"), text);
        assertTrue(text.contains("org.lib:lib  – 3 Versionen"), text);
        assertTrue(text.contains("    1.0: a (direkt angegeben)"), text);
        assertTrue(text.contains("    2.0: b (transitiv (Tiefe 2))"), text);
        assertTrue(text.contains("Vorschlag: 2.0 in parent"), text);
        assertFalse(text.contains("org.x:x"), text);
        assertTrue(String.join("\n", m.lines(true)).contains("org.x:x"));

        String md = m.markdown(false);
        assertTrue(md.contains("| Artefakt | a | b | c | Vorschlag |"), md);
        assertTrue(md.contains("| `org.lib:lib` | **1.0** | 2.0 | **1.5** | 2.0 in `parent` (2 Stellen überstimmen ihn) |"), md);
    }
}
