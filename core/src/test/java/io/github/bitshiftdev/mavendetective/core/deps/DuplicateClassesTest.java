package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses.Clash;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath.Entry;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.fix.FixExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lokales Repository mit echten (winzigen) JARs:
 * <ul>
 *   <li>web bringt commons-logging; das Projekt hat jcl-over-slf4j direkt → bekanntes Paar</li>
 *   <li>fat (direkt) enthält alle Klassen von small; small kommt über c → small kann weg</li>
 *   <li>d1 und e1 teilen eine Klasse mit verschiedenem Inhalt, haben aber auch eigene → Entscheidung nötig</li>
 *   <li>t1 und t2 nur als {@code test} → nur Test-Classpath</li>
 * </ul>
 */
class DuplicateClassesTest {

    @TempDir
    Path tmp;
    Path project;
    Path repo;

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** POM + JAR; {@code classes} als „name=inhalt“ (Inhalt bestimmt die CRC). */
    void artifact(String g, String a, String v, String deps, String... classes) throws IOException {
        Path dir = repo;
        for (String s : g.split("\\.")) {
            dir = dir.resolve(s);
        }
        dir = dir.resolve(a).resolve(v);
        write(dir.resolve(a + "-" + v + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version>" + (deps.isEmpty() ? "" : "<dependencies>" + deps + "</dependencies>")
                + "</project>");
        jar(dir.resolve(a + "-" + v + ".jar"), classes);
    }

    static void jar(Path file, String... classes) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            for (String c : classes) {
                String[] nv = c.split("=", 2);
                String path = nv[0].replace('.', '/') + ".class";
                zip.putNextEntry(new ZipEntry(path));
                zip.write((nv.length > 1 ? nv[1] : nv[0]).getBytes(StandardCharsets.UTF_8));
            }
            // innere Klassen, module-info und Multi-Release zählen nicht
            zip.putNextEntry(new ZipEntry("module-info.class"));
            zip.write(new byte[] {1});
            zip.putNextEntry(new ZipEntry("META-INF/versions/11/x/Ignored.class"));
            zip.write(new byte[] {2});
        }
    }

    static String dep(String g, String a, String v, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</dependency>";
    }

    @BeforeEach
    void setUp() throws IOException {
        project = tmp.resolve("project");
        repo = tmp.resolve("repo");
        artifact("org.web", "web", "1.0", dep("commons-logging", "commons-logging", "1.2", ""), "org.web.Web");
        artifact("commons-logging", "commons-logging", "1.2", "",
                "org.apache.commons.logging.Log=orig", "org.apache.commons.logging.LogFactory=orig", "org.apache.commons.logging.impl.SimpleLog");
        artifact("org.slf4j", "jcl-over-slf4j", "1.7.36", "",
                "org.apache.commons.logging.Log=bridge", "org.apache.commons.logging.LogFactory=bridge");
        artifact("org.a", "fat", "1.0", "", "com.x.A", "com.x.B", "com.y.Own", "com.x.A$Inner");
        artifact("org.c", "c", "1.0", dep("org.b", "small", "1.0", ""), "org.c.C");
        artifact("org.b", "small", "1.0", "", "com.x.A", "com.x.B");
        artifact("org.d", "d1", "1.0", "", "com.z.Z=d", "org.d.Only");
        artifact("org.e", "e1", "1.0", "", "com.z.Z=e", "org.e.Only");
        artifact("org.t", "t1", "1.0", "", "org.t.T=1");
        artifact("org.t", "t2", "1.0", "", "org.t.T=2");

        write(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                  </dependencies>
                </project>
                """.formatted(
                dep("org.web", "web", "1.0", ""),
                dep("org.slf4j", "jcl-over-slf4j", "1.7.36", ""),
                dep("org.a", "fat", "1.0", ""),
                dep("org.c", "c", "1.0", ""),
                dep("org.d", "d1", "1.0", ""),
                dep("org.e", "e1", "1.0", ""),
                dep("org.t", "t1", "1.0", "<scope>test</scope>"),
                dep("org.t", "t2", "1.0", "<scope>test</scope>")));
    }

    private DuplicateClasses run(Map<String, DepNode> trees) {
        PomLocator locator = new PomLocator(project, repo);
        PomFile pom = locator.load(project.resolve("pom.xml"));
        return DuplicateClasses.build(locator, List.of(pom), trees, new JarIndex(), null);
    }

    private static Clash clash(DuplicateClasses d, String className) {
        for (Clash c : d.clashes()) {
            if (c.getClasses().contains(className)) {
                return c;
            }
        }
        return null;
    }

    private static List<String> gas(Clash c) {
        List<String> out = new ArrayList<>();
        for (Entry e : c.getJars()) {
            out.add(e.ga());
        }
        return out;
    }

    @Test
    void jarIndexSkipsInnerClassesModuleInfoAndMetaInf() throws IOException {
        Path jar = repo.resolve("org/a/fat/1.0/fat-1.0.jar");
        JarIndex.Contents c = new JarIndex().read(jar);
        assertEquals(List.of("com.x.A", "com.x.B", "com.y.Own"), JarIndex.names(c));
        assertEquals(c.crc("com.x.A"), new JarIndex().read(repo.resolve("org/b/small/1.0/small-1.0.jar")).crc("com.x.A"));
    }

    @Test
    void knownPairRecommendsDroppingCommonsLogging() {
        DuplicateClasses d = run(Map.of());
        assertTrue(d.isEstimated());
        Clash c = clash(d, "org.apache.commons.logging.Log");
        assertNotNull(c);
        assertEquals(List.of("org.apache.commons.logging.Log", "org.apache.commons.logging.LogFactory"), c.getClasses());
        assertEquals(2, c.getDiffering());
        assertTrue(c.isMain());
        assertEquals("commons-logging:commons-logging", c.getDrop().ga());
        assertNotNull(c.getHint());
        List<FixAction> fixes = c.fixes();
        assertEquals(1, fixes.size());
        assertEquals(FixAction.ADD_EXCLUSION, fixes.get(0).getType());
        assertEquals("org.web:web", fixes.get(0).param("artifact"));
        assertEquals("commons-logging:commons-logging", fixes.get(0).param("exclusion"));
        assertEquals(project.resolve("pom.xml").toString(), fixes.get(0).param("file"));
    }

    @Test
    void fullyContainedJarCanGo() {
        Clash c = clash(run(Map.of()), "com.x.A");
        assertNotNull(c);
        assertEquals(List.of("org.a:fat", "org.b:small"), gas(c));
        assertTrue(c.isIdentical());
        assertEquals("org.b:small", c.getDrop().ga());
        assertTrue(c.getReason().contains("inhaltsgleich"), c.getReason());
        assertEquals("org.c:c", c.fixes().get(0).param("artifact"));
    }

    @Test
    void sharedClassWithOwnClassesNeedsDecision() {
        Clash c = clash(run(Map.of()), "com.z.Z");
        assertNotNull(c);
        assertEquals(List.of("org.d:d1", "org.e:e1"), gas(c));
        assertFalse(c.isIdentical());
        assertNull(c.getDrop());
        assertTrue(c.fixes().isEmpty());
        assertTrue(c.recommendation().contains("Heute gewinnt d1:1.0"), c.recommendation());
        // trotzdem lässt sich jede Seite gezielt ausschließen: hier beide direkt → Sprung zur Deklaration
        assertEquals(FixAction.OPEN_FILE, c.fixesFor(c.getJars().get(1)).get(0).getType());
    }

    @Test
    void testScopeOnlyIsMarked() {
        DuplicateClasses d = run(Map.of());
        Clash c = clash(d, "org.t.T");
        assertNotNull(c);
        assertFalse(c.isMain());
        // Reihenfolge: Laufzeit und verschiedener Inhalt zuerst, nur-Test zuletzt
        assertEquals(c, d.clashes().get(d.clashes().size() - 1));
        assertEquals(4, d.clashes().size());
        assertEquals(2, d.fixable().size());
    }

    @Test
    void treeDecidesOrderAndAllPathsGetExclusions() {
        // Mit Baum: commons-logging kommt über web (gewinnt) und verdrängt nochmal über c
        DepNode root = new DepNode("com.acme", "app", "jar", null, "1.0", null);
        DepNode web = root.add(new DepNode("org.web", "web", "jar", null, "1.0", "compile"));
        web.add(new DepNode("commons-logging", "commons-logging", "jar", null, "1.2", "compile"));
        root.add(new DepNode("org.slf4j", "jcl-over-slf4j", "jar", null, "1.7.36", "compile"));
        DepNode c = root.add(new DepNode("org.c", "c", "jar", null, "1.0", "compile"));
        c.add(new DepNode("commons-logging", "commons-logging", "jar", null, "1.1", "compile"))
                .setState(DepNode.State.CONFLICT).setRelatedVersion("1.2");
        c.add(new DepNode("org.b", "small", "jar", null, "1.0", "compile"));
        DuplicateClasses d = run(Map.of("com.acme:app", root));
        assertFalse(d.isEstimated());
        Clash cl = clash(d, "org.apache.commons.logging.Log");
        assertEquals(List.of("commons-logging:commons-logging", "org.slf4j:jcl-over-slf4j"), gas(cl));
        assertTrue(cl.message().contains("geladen wird die aus commons-logging:1.2"), cl.message());
        List<String> targets = new ArrayList<>();
        for (FixAction f : cl.fixes()) {
            targets.add(f.param("artifact"));
        }
        assertEquals(List.of("org.web:web", "org.c:c"), targets);
    }

    @Test
    void missingJarIsReportedNotGuessed() throws IOException {
        Files.delete(repo.resolve("org/e/e1/1.0/e1-1.0.jar"));
        DuplicateClasses d = run(Map.of());
        assertNull(clash(d, "com.z.Z"));
        assertEquals("org.e:e1", d.getModules().get(0).getUnreadable().get(0).ga());
        assertTrue(String.join("\n", d.lines(false)).contains("nicht geprüft (JAR fehlt lokal oder ist kaputt): org.e:e1:1.0"));
    }

    @Test
    void ownClassesCountAsEntry() throws IOException {
        Path classes = project.resolve("target/classes/org/d");
        Files.createDirectories(classes);
        Files.write(classes.resolve("Only.class"), "own".getBytes(StandardCharsets.UTF_8));
        Clash c = clash(run(Map.of()), "org.d.Only");
        assertNotNull(c);
        assertTrue(c.getJars().get(0).isSelf());
        assertEquals("org.d:d1", c.getDrop() == null ? null : c.getDrop().ga());
    }

    @Test
    void applyingTheFixRemovesTheClash() throws IOException {
        DuplicateClasses d = run(Map.of());
        FixExecutor ex = new FixExecutor(List.of());
        for (Clash c : d.fixable()) {
            for (FixAction f : c.fixes()) {
                ex.execute(f);
            }
        }
        DuplicateClasses after = run(Map.of());
        assertNull(clash(after, "org.apache.commons.logging.Log"));
        assertNull(clash(after, "com.x.A"));
        assertEquals(2, after.clashes().size());
        String pom = Files.readString(project.resolve("pom.xml"));
        assertTrue(pom.contains("<exclusion><groupId>") || pom.contains("<exclusion>\n"), pom);
    }

    @Test
    void outputMentionsRecommendation() {
        DuplicateClasses d = run(Map.of());
        String text = String.join("\n", d.lines(false));
        assertTrue(text.startsWith("Doppelte Klassen: 4 Funde in 1 Modul – 3 mit verschiedenem Inhalt, 3 im Laufzeit-Classpath"), text);
        assertTrue(text.contains("Empfehlung: commons-logging:1.2 entfernen: bekanntes Paar"), text);
        assertTrue(text.contains("→ Exclusion commons-logging:commons-logging bei org.web:web einfügen"), text);
        String md = d.markdown(false);
        assertTrue(md.contains("| `app` | `small:1.0`") || md.contains("`fat:1.0`, `small:1.0`"), md);
    }
}
