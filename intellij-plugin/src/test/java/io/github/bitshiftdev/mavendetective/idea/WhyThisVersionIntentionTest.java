package io.github.bitshiftdev.mavendetective.idea;

/** Alt+Enter „Warum diese Version?“ gibt es nur auf einer {@code <dependency>} einer Projekt-POM. */
public class WhyThisVersionIntentionTest extends PomFixtureTestCase {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example.app</groupId>
              <artifactId>app</artifactId>
              <version>1</version>
              <properties>
                <java.version>17</java.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>direct</artifactId>
                  <version>1.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private String text;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        text = MavenDetectiveBundle.message("action.MavenDetective.WhyThisVersion.text");
        myFixture.configureFromExistingVirtualFile(writePom(POM));
    }

    public void testAufEinerDependencyVerfuegbar() {
        caretAt("<artifactId>direct");
        assertEquals(1, myFixture.filterAvailableIntentions(text).size());
    }

    public void testAusserhalbEinerDependencyNichtVerfuegbar() {
        caretAt("<java.version>");
        assertEmpty(myFixture.filterAvailableIntentions(text));
    }

    public void testNichtInAnderenXmlDateien() throws Exception {
        myFixture.configureFromExistingVirtualFile(write(root.resolve("other.xml"), POM));
        caretAt("<artifactId>direct");
        assertEmpty(myFixture.filterAvailableIntentions(text));
    }

    private void caretAt(String marker) {
        int offset = myFixture.getEditor().getDocument().getText().indexOf(marker);
        assertTrue(marker, offset >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(offset + 1);
        settle();
    }
}
