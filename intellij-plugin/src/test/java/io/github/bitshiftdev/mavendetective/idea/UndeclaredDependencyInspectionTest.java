package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api;
import io.github.bitshiftdev.mavendetective.idea.testlib.transitive.Helper;

import java.nio.file.Path;
import java.util.List;

/** A5 live: Import aus einem nur transitiv vorhandenen JAR wird markiert, der Quick-Fix deklariert es in der POM. */
public class UndeclaredDependencyInspectionTest extends PomFixtureTestCase {

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
                </dependency>
              </dependencies>
            </project>
            """;

    private static final String APP = """
            package org.example.app;

            import io.github.bitshiftdev.mavendetective.idea.testlib.direct.Api;
            import io.github.bitshiftdev.mavendetective.idea.testlib.transitive.Helper;

            public class App {
                Api api;
                Helper helper;
            }
            """;

    private VirtualFile pom;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        repoPom("org.example", "direct", "1.0", "org.example:transitive:1.0");
        repoPom("org.example", "transitive", "1.0");
        repoJarAsLibrary("org.example", "direct", "1.0", Api.class);
        repoJarAsLibrary("org.example", "transitive", "1.0", Helper.class);
        pom = writePom(POM);
        myFixture.enableInspections(new UndeclaredDependencyInspection());
    }

    public void testNurDerTransitiveImportWirdMarkiert() throws Exception {
        configureApp();

        List<HighlightInfo> found = findings();

        assertEquals(found.toString(), 1, found.size());
        assertEquals(Helper.class.getName(), found.get(0).getText());
    }

    public void testQuickFixDeklariertDieDependency() throws Exception {
        configureApp();
        List<HighlightInfo> found = findings();
        assertEquals(found.toString(), 1, found.size());
        myFixture.getEditor().getCaretModel().moveToOffset(found.get(0).getStartOffset() + 1);
        String family = MavenDetectiveBundle.message("inspection.undeclared.family");
        List<IntentionAction> fixes = myFixture.getAllQuickFixes().stream()
                .filter(a -> family.equals(a.getFamilyName()))
                .toList();
        assertEquals(fixes.toString(), 1, fixes.size());

        myFixture.launchAction(fixes.get(0));

        List<String> declared = PomFile.parse(pom.toNioPath(), new String(pom.contentsToByteArray(), pom.getCharset()))
                .getDependencies().stream().map(PomFile.Dep::ga).toList();
        assertEquals(List.of("org.example:direct", "org.example:transitive"), declared);
        assertEquals(List.of(pom), reimported);
    }

    private void configureApp() throws Exception {
        Path app = root.resolve("org/example/app/App.java");
        myFixture.configureFromExistingVirtualFile(write(app, APP));
    }

    /** Befunde dieser Inspection (auf der Import-Referenz; Stufe laut plugin.xml schwache Warnung). */
    private List<HighlightInfo> findings() {
        settle();
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).stream()
                .filter(h -> h.getText() != null && h.getText().startsWith("io.github.bitshiftdev.mavendetective.idea.testlib."))
                .toList();
    }
}
