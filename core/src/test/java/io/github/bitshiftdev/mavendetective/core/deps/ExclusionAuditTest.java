package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit.Finding;
import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit.Status;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lokales Repository: {@code org.web:web:1.0} (Parent setzt {@code mid.version}) bringt
 * commons-logging und mid → deep transitiv; old nur als {@code test}, opt nur {@code optional}.
 * Das Projekt schließt bei web mehrere Artifacts aus – einige wirken, andere nicht mehr.
 */
class ExclusionAuditTest {

    @TempDir
    Path tmp;
    Path project;
    Path repo;

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    void artifact(String g, String a, String v, String body) throws IOException {
        Path dir = repo;
        for (String s : g.split("\\.")) {
            dir = dir.resolve(s);
        }
        write(dir.resolve(a).resolve(v).resolve(a + "-" + v + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                %s
                </project>
                """.formatted(g, a, v, body));
    }

    static String dep(String g, String a, String v, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</dependency>";
    }

    @BeforeEach
    void setUp() throws IOException {
        project = tmp.resolve("project");
        repo = tmp.resolve("repo");
        artifact("org.web", "web-parent", "1.0", "<packaging>pom</packaging><properties><mid.version>1.0</mid.version></properties>");
        Path webDir = repo.resolve("org/web/web/1.0");
        write(webDir.resolve("web-1.0.pom"), """
                <project>
                  <parent><groupId>org.web</groupId><artifactId>web-parent</artifactId><version>1.0</version></parent>
                  <artifactId>web</artifactId>
                  <dependencies>
                    %s
                    %s
                    %s
                    %s
                  </dependencies>
                </project>
                """.formatted(
                dep("commons-logging", "commons-logging", "1.2", ""),
                dep("org.mid", "mid", "${mid.version}", ""),
                dep("org.old", "old", "1.0", "<scope>test</scope>"),
                dep("org.opt", "opt", "1.0", "<optional>true</optional>")));
        artifact("commons-logging", "commons-logging", "1.2", "");
        artifact("org.mid", "mid", "1.0", "<dependencies>" + dep("org.deep", "deep", "1.0", "") + "</dependencies>");
        artifact("org.deep", "deep", "1.0", "");
        artifact("org.other", "other", "1.0", "<dependencies>" + dep("commons-logging", "commons-logging", "1.2", "") + "</dependencies>");

        write(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.web</groupId>
                        <artifactId>web</artifactId>
                        <version>1.0</version>
                        <exclusions>
                          <exclusion>
                            <groupId>org.gone</groupId>
                            <artifactId>gone2</artifactId>
                          </exclusion>
                        </exclusions>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.web</groupId>
                      <artifactId>web</artifactId>
                      <exclusions>
                        <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>
                        <exclusion><groupId>org.old</groupId><artifactId>old</artifactId></exclusion>
                        <exclusion><groupId>org.opt</groupId><artifactId>opt</artifactId></exclusion>
                        <exclusion><groupId>org.mid</groupId><artifactId>mid</artifactId></exclusion>
                        <exclusion><groupId>org.deep</groupId><artifactId>deep</artifactId></exclusion>
                        <exclusion><groupId>org.gone</groupId><artifactId>gone</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>org.other</groupId>
                      <artifactId>other</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.missing</groupId>
                      <artifactId>missing</artifactId>
                      <version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    ExclusionAudit audit(Map<String, DepNode> trees) {
        PomLocator locator = new PomLocator(project, repo);
        return ExclusionAudit.build(locator, List.of(locator.load(project.resolve("pom.xml"))), trees);
    }

    static Finding finding(ExclusionAudit a, String exclusion) {
        for (Finding f : a.getFindings()) {
            if (f.getExclusion().ga().equals(exclusion)) {
                return f;
            }
        }
        return null;
    }

    @Test
    void classifiesEachExclusion() {
        ExclusionAudit a = audit(Map.of());
        assertEquals(8, a.getFindings().size());

        Finding logging = finding(a, "commons-logging:commons-logging");
        assertEquals(Status.USED, logging.getStatus());
        assertEquals(List.of("org.web:web:1.0", "commons-logging:commons-logging:1.2"), logging.getPath());
        assertEquals("1.0", logging.getDependencyVersion(), "Version aus dem eigenen dependencyManagement");
        assertNull(logging.fix());

        assertEquals(Status.STALE, finding(a, "org.old:old").getStatus(), "test-Scope ist nicht transitiv");
        assertEquals(Status.STALE, finding(a, "org.opt:opt").getStatus(), "optional ist nicht transitiv");
        assertEquals(Status.STALE, finding(a, "org.gone:gone").getStatus());

        Finding mid = finding(a, "org.mid:mid");
        assertEquals(Status.USED, mid.getStatus(), "Version über Property im Parent der Dependency");
        assertEquals("org.mid:mid:1.0", mid.getPath().get(1));

        Finding deep = finding(a, "org.deep:deep");
        assertEquals(Status.REDUNDANT, deep.getStatus());
        assertEquals("org.mid:mid", deep.getCoveredBy());
        assertTrue(deep.message().contains("org.web:web:1.0 → org.mid:mid:1.0 → org.deep:deep:1.0"), deep.message());

        Finding gone2 = finding(a, "org.gone:gone2");
        assertTrue(gone2.isManaged());
        assertEquals(Status.STALE, gone2.getStatus());
        assertEquals(project.resolve("pom.xml").toString(), gone2.getFile());
        assertEquals(13, gone2.getLine());

        Finding unknown = finding(a, "x:y");
        assertEquals(Status.UNKNOWN, unknown.getStatus(), "POM fehlt lokal – keine Aussage");
        assertEquals(List.of("org.missing:missing:1.0"), unknown.getMissing());

        assertEquals(5, a.removable().size());
        assertEquals(1, a.count(Status.UNKNOWN));
    }

    @Test
    void wildcardAndTransitiveExclusionsAndManagedVersions() throws IOException {
        artifact("org.web", "web", "2.0", "<dependencies>" + dep("org.mid", "mid", "1.0",
                "<exclusions><exclusion><groupId>org.deep</groupId><artifactId>deep</artifactId></exclusion></exclusions>") + "</dependencies>");
        artifact("org.mid", "mid", "0.9", "");
        write(project.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencyManagement><dependencies>%s</dependencies></dependencyManagement>
                  <dependencies>
                    %s
                    %s
                    %s
                  </dependencies>
                </project>
                """.formatted(
                dep("org.mid", "mid", "0.9", ""),
                dep("org.web", "web", "2.0", "<exclusions><exclusion><groupId>org.deep</groupId><artifactId>*</artifactId></exclusion></exclusions>"),
                dep("org.mid", "mid", "1.0", "<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>"),
                dep("org.deep", "deep", "1.0", "<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>")));
        ExclusionAudit a = audit(Map.of());
        assertEquals(Status.STALE, a.getFindings().get(0).getStatus(),
                "org.deep:* – web 2.0 schließt deep selbst schon aus, und mid 0.9 (Management des Moduls) hat keine Dependencies");
        assertEquals(Status.USED, a.getFindings().get(1).getStatus(), "*:* bei mid 1.0, das deep mitbringt");
        assertEquals(Status.STALE, a.getFindings().get(2).getStatus(), "*:* bei deep, das nichts mitbringt");
    }

    @Test
    void treeShowsArtifactStillOnClasspath() throws IOException {
        Map<String, DepNode> trees = new HashMap<>();
        for (DepNode n : DependencyTreeParser.parse(new StringReader("""
                [INFO] com.acme:app:jar:1.0
                [INFO] +- org.web:web:jar:1.0:compile
                [INFO] \\- org.other:other:jar:1.0:compile
                [INFO]    \\- commons-logging:commons-logging:jar:1.2:compile
                """))) {
            trees.put(n.ga(), n);
        }
        Finding logging = finding(audit(trees), "commons-logging:commons-logging");
        assertEquals(Status.USED, logging.getStatus());
        assertEquals("org.other:other:1.0 → commons-logging:commons-logging:1.2", logging.getAlsoIncludedVia());
        assertTrue(logging.message().contains("landet trotzdem im Classpath"), logging.message());
    }

    @Test
    void fixRemovesExclusionAndAuditSeesIt() throws IOException {
        Finding gone2 = finding(audit(Map.of()), "org.gone:gone2");
        FixAction fix = gone2.fix();
        assertNotNull(fix);
        assertEquals(FixAction.REMOVE_EXCLUSION, fix.getType());
        assertEquals("dependencyManagement", fix.param("section"));
        FixExecutor.Outcome o = new FixExecutor(List.of()).execute(fix);
        assertTrue(o.actions.get(0).contains("<exclusions> mit org.gone:gone2"), o.actions.toString());

        String pom = Files.readString(project.resolve("pom.xml"));
        assertFalse(pom.contains("gone2"));
        assertTrue(pom.contains("        <version>1.0</version>\n      </dependency>\n    </dependencies>\n  </dependencyManagement>"), pom);

        Finding old = finding(audit(Map.of()), "org.old:old");
        new FixExecutor(List.of()).execute(old.fix());
        ExclusionAudit after = audit(Map.of());
        assertNull(finding(after, "org.old:old"));
        assertNull(finding(after, "org.gone:gone2"));
        assertEquals(Status.USED, finding(after, "commons-logging:commons-logging").getStatus());
        assertTrue(Files.readString(project.resolve("pom.xml")).contains(
                "<exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>\n"
                        + "        <exclusion><groupId>org.opt</groupId>"));
    }

    @Test
    void textAndMarkdown() {
        ExclusionAudit a = audit(Map.of());
        List<String> lines = a.lines(false);
        assertEquals("Exclusion-Hygiene: 8 Exclusions in 1 POM – 4 veraltet, 1 doppelt, 1 nicht prüfbar", lines.get(0));
        String text = String.join("\n", lines);
        assertFalse(text.contains("wirkt"), "ohne --all keine wirksamen");
        assertTrue(String.join("\n", a.lines(true)).contains("wirkt"));
        assertTrue(a.markdown(false).contains("| veraltet | `app:"), a.markdown(false));
    }
}
