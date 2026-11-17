package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;

/** A4 im Editor: veraltete Exclusion wird markiert, der Quick-Fix entfernt genau dieses Tag. */
public class StaleExclusionInspectionTest extends PomFixtureTestCase {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example.app</groupId>
              <artifactId>app</artifactId>
              <version>1</version>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>direct</artifactId>
                  <version>1.0</version>
                  <exclusions>
                    <exclusion>
                      <groupId>org.example</groupId>
                      <artifactId>gone</artifactId>
                    </exclusion>
                    <exclusion>
                      <groupId>org.example</groupId>
                      <artifactId>transitive</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
              </dependencies>
            </project>
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // direct bringt nur noch transitive mit: die Exclusion von gone ist veraltet, die von transitive wirkt
        repoPom("org.example", "direct", "1.0", "org.example:transitive:1.0");
        repoPom("org.example", "transitive", "1.0");
        myFixture.enableInspections(new StaleExclusionInspection());
    }

    public void testVeralteteExclusionWirdMarkiert() throws Exception {
        myFixture.configureFromExistingVirtualFile(writePom(POM));

        List<HighlightInfo> found = exclusionHighlights();

        assertEquals(found.toString(), 1, found.size());
        assertTrue(found.get(0).getText(), found.get(0).getText().contains("<artifactId>gone</artifactId>"));
    }

    public void testQuickFixEntferntNurDieVeralteteExclusion() throws Exception {
        VirtualFile pom = writePom(POM);
        myFixture.configureFromExistingVirtualFile(pom);
        List<HighlightInfo> found = exclusionHighlights();
        assertEquals(found.toString(), 1, found.size());
        myFixture.getEditor().getCaretModel().moveToOffset(found.get(0).getStartOffset() + 1);

        IntentionAction fix = myFixture.findSingleIntention(
                MavenDetectiveBundle.message("inspection.stale.exclusion.fix", "org.example:gone"));
        myFixture.launchAction(fix);

        String after = myFixture.getEditor().getDocument().getText();
        assertFalse(after, after.contains("<artifactId>gone</artifactId>"));
        assertTrue(after, after.contains("<artifactId>transitive</artifactId>"));
        assertTrue(after, after.contains("<exclusions>"));
        assertEquals(POM.replace("""
                        <exclusion>
                          <groupId>org.example</groupId>
                          <artifactId>gone</artifactId>
                        </exclusion>
                """, ""), after);
        assertEquals(List.of(pom), reimported);
    }

    /** Nur die Befunde dieser Inspection: Sie markiert ganze {@code <exclusion>}-Tags. */
    private List<HighlightInfo> exclusionHighlights() {
        settle();
        return myFixture.doHighlighting().stream()
                .filter(h -> h.getText() != null && h.getText().startsWith("<exclusion>"))
                .toList();
    }
}
