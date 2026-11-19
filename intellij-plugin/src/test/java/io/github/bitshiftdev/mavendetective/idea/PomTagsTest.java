package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** {@link PomTags}: Befunde gehen über die Struktur aufs PSI, nie über Zeilen. */
public class PomTagsTest extends PomFixtureTestCase {

    private static final String POM = """
            <project>
              <groupId>org.example</groupId>
              <artifactId>app</artifactId>
              <dependencies>
                <dependency>
                  <groupId>${project.groupId}</groupId>
                  <artifactId>sibling</artifactId>
                </dependency>
                <dependency>
                  <groupId> org.lib </groupId>
                  <artifactId>lib</artifactId>
                  <type>test-jar</type>
                  <classifier>tests</classifier>
                </dependency>
                <dependency>
                  <groupId>org.lib</groupId>
                  <artifactId>   </artifactId>
                </dependency>
              </dependencies>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.bom</groupId>
                    <artifactId>bom</artifactId>
                    <type>pom</type>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <profiles>
                <profile>
                  <dependencies>
                    <dependency><groupId>org.p</groupId><artifactId>in-profile</artifactId></dependency>
                  </dependencies>
                </profile>
              </profiles>
              <build>
                <plugins>
                  <plugin>
                    <dependencies>
                      <dependency><groupId>org.p</groupId><artifactId>plugin-dep</artifactId></dependency>
                    </dependencies>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private XmlTag project;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.configureFromExistingVirtualFile(writePom(POM));
        project = PomTags.projectTag(myFixture.getFile());
        assertNotNull(project);
    }

    private List<String> artifactIds(List<XmlTag> tags) {
        List<String> out = new ArrayList<>();
        for (XmlTag t : tags) {
            out.add(PomTags.text(t, "artifactId"));
        }
        return out;
    }

    private List<XmlTag> allDependencyTags() {
        return new ArrayList<>(PsiTreeUtil.findChildrenOfType(myFixture.getFile(), XmlTag.class).stream()
                .filter(t -> "dependency".equals(t.getLocalName())).toList());
    }

    private XmlTag dependency(String artifactId) {
        for (XmlTag t : allDependencyTags()) {
            if (artifactId.equals(t.getSubTagText("artifactId"))) {
                return t;
            }
        }
        throw new AssertionError(artifactId);
    }

    private PsiFile psi(com.intellij.openapi.vfs.VirtualFile file) {
        return com.intellij.psi.PsiManager.getInstance(getProject()).findFile(file);
    }

    public void testProjektTagNurFuerPomXmlAufDerPlatte() throws Exception {
        PsiFile other = myFixture.configureByText("pom.xml", POM);   // im Speicher, nicht im lokalen Dateisystem
        assertNull(PomTags.projectTag(other));
        assertNull("anderer Dateiname", PomTags.projectTag(psi(write(root.resolve("sub/build.xml"), POM))));
        assertNull("Wurzel ist nicht <project>", PomTags.projectTag(psi(write(root.resolve("x/pom.xml"), "<settings/>"))));
        assertNull(PomTags.projectTag(null));
    }

    public void testDependenciesOhneProfileUndPlugins() {
        assertEquals(Arrays.asList("sibling", "lib", null), artifactIds(PomTags.dependencies(project, false)));
        assertEquals(Arrays.asList("sibling", "lib", null, "bom"), artifactIds(PomTags.dependencies(project, true)));
    }

    public void testEbeneDerDependency() {
        assertTrue(PomTags.isProjectDependency(dependency("lib")));
        assertFalse(PomTags.isProjectDependency(dependency("bom")));
        assertTrue(PomTags.isTopLevelDependency(dependency("bom")));
        for (String nested : List.of("in-profile", "plugin-dep")) {
            assertFalse(nested, PomTags.isProjectDependency(dependency(nested)));
            assertFalse(nested, PomTags.isTopLevelDependency(dependency(nested)));
        }
        assertFalse(PomTags.isTopLevelDependency(project));
    }

    public void testKoordinatenMitPlatzhalterUndLeerraum() {
        assertEquals("org.example:sibling", PomTags.ga(dependency("sibling"), "org.example"));
        assertNull("Platzhalter ohne bekannte groupId", PomTags.ga(dependency("sibling"), null));
        assertEquals("org.lib:lib", PomTags.ga(dependency("lib"), null));
        assertEquals("org.lib:lib:test-jar:tests", PomTags.managementKey(dependency("lib")));
        assertEquals("org.bom:bom:pom", PomTags.managementKey(dependency("bom")));
        XmlTag blank = PomTags.dependencies(project, false).get(2);
        assertNull("leere artifactId", PomTags.ga(blank, "org.example"));
        assertNull(PomTags.managementKey(blank));
    }

    public void testNurDerNameImOeffnendenTagLiefertDieDependency() {
        XmlTag lib = dependency("lib");
        List<XmlToken> names = new ArrayList<>();
        for (PsiElement c = lib.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof XmlToken t && t.getTokenType() == XmlTokenType.XML_NAME) {
                names.add(t);
            }
        }
        assertEquals("öffnender und schließender Name", 2, names.size());
        assertSame(lib, PomTags.dependencyOfNameToken(names.get(0)));
        assertNull(PomTags.dependencyOfNameToken(names.get(1)));
        assertNull("Name eines anderen Tags", PomTags.dependencyOfNameToken(
                PsiTreeUtil.findChildOfType(lib.findFirstSubTag("groupId"), XmlToken.class)));
        assertNull("kein Token", PomTags.dependencyOfNameToken(lib));
    }
}
