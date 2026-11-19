package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomHints;

import java.nio.file.Files;

/** {@link PomInsights#facts}: Befunde einer POM für Inlays, Code Vision und Gutter – gecacht bis zur nächsten Grundlage. */
public class PomInsightsTest extends PomFixtureTestCase {

    private static final String PARENT = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>parent</artifactId>
              <version>1</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.lib</groupId>
                    <artifactId>lib</artifactId>
                    <version>%s</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>org.example</groupId>
                <artifactId>parent</artifactId>
                <version>1</version>
                <relativePath>parent/pom.xml</relativePath>
              </parent>
              <artifactId>app</artifactId>
              <dependencies>
                <dependency>
                  <groupId>org.lib</groupId>
                  <artifactId>lib</artifactId>
                </dependency>
              </dependencies>
            </project>
            """;

    /** Ohne {@link #settle()}: das setzt eine (leere) Matrix und verwirft damit den Cache. */
    private PomInsights.Facts facts(VirtualFile pom) {
        XmlFile xml = (XmlFile) PsiManager.getInstance(getProject()).findFile(pom);
        assertNotNull(xml);
        return ReadAction.compute(() -> PomInsights.getInstance(getProject()).facts(xml));
    }

    public void testVerwalteteVersionKommtAusDemParent() throws Exception {
        write(root.resolve("parent/pom.xml"), PARENT.formatted("2.5"));
        VirtualFile pom = writePom(POM);
        settle();

        PomInsights.Facts f = facts(pom);

        assertEquals(pom.toNioPath(), f.pom());
        PomHints.ManagedVersion lib = f.managed().get("org.lib:lib:jar");
        assertNotNull(f.managed().toString(), lib);
        assertEquals("2.5", lib.getVersion());
        assertEquals(root.resolve("parent/pom.xml").toString(), lib.getFile());
        assertTrue(lib.getLine() > 0);
        assertTrue("ohne Matrix keine Konflikte", f.conflicts().isEmpty());
        assertTrue("ohne Prüfung keine doppelten Klassen", f.clashes().isEmpty());
    }

    public void testGespeicherteParentPomLiefertNeueBefunde() throws Exception {
        write(root.resolve("parent/pom.xml"), PARENT.formatted("2.5"));
        VirtualFile pom = writePom(POM);
        settle();
        PomInsights.Facts first = facts(pom);
        assertSame("gecacht", first, facts(pom));

        write(root.resolve("parent/pom.xml"), PARENT.formatted("3.0"));
        settle();

        PomInsights.Facts second = facts(pom);
        assertNotSame(first, second);
        assertEquals("3.0", second.managed().get("org.lib:lib:jar").getVersion());
    }

    public void testNeueMatrixOderPruefungVerwirftDenCache() throws Exception {
        write(root.resolve("parent/pom.xml"), PARENT.formatted("2.5"));
        VirtualFile pom = writePom(POM);
        settle();
        PomInsights.Facts first = facts(pom);
        assertSame(first, facts(pom));
        PomInsights.getInstance(getProject()).setDuplicates(null);
        assertNotSame(first, facts(pom));
    }

    public void testKaputtePomLiefertLeereBefundeStattFehler() throws Exception {
        VirtualFile pom = writePom("<project><dependencies><dependency>");
        PomInsights.Facts f = facts(pom);
        assertTrue(f.managed().isEmpty());
        assertTrue(f.risks().isEmpty());
        assertTrue(Files.exists(pom.toNioPath()));
    }

    public void testFehlenderParentLiefertKeineVerwalteteVersion() throws Exception {
        VirtualFile pom = writePom(POM);
        assertTrue(facts(pom).managed().isEmpty());
    }
}
