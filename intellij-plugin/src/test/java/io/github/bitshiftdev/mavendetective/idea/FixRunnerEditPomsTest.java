package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** {@link FixRunner#editPoms}: planen → anwenden → ein Undo-Schritt, speichern, Maven-Import. */
public class FixRunnerEditPomsTest extends PomFixtureTestCase {

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

    public void testZweiFixesSindEinUndoSchritt() throws Exception {
        VirtualFile pom = writePom(POM);
        myFixture.configureFromExistingVirtualFile(pom);
        Document doc = myFixture.getEditor().getDocument();
        FileEditor editor = TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
        UndoManager undo = UndoManager.getInstance(getProject());
        assertFalse(undo.isUndoAvailable(editor));

        int written = FixRunner.editPoms(getProject(), List.of(exclusion("org.example:a"), exclusion("org.example:b")), "Test");

        assertEquals(2, written);
        String after = doc.getText();
        assertTrue(after, after.contains("<artifactId>a</artifactId>"));
        assertTrue(after, after.contains("<artifactId>b</artifactId>"));
        // gespeichert und Maven-Import angestoßen
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
        assertEquals(after, Files.readString(pom.toNioPath(), StandardCharsets.UTF_8));
        assertEquals(List.of(pom), reimported);

        assertTrue(undo.isUndoAvailable(editor));
        undo.undo(editor);
        assertEquals(POM, doc.getText());
        assertFalse("beide Exclusions sind ein einziger Undo-Schritt", undo.isUndoAvailable(editor));
    }

    public void testNichtAnwendbarerFixAendertNichts() throws Exception {
        VirtualFile pom = writePom(POM);
        myFixture.configureFromExistingVirtualFile(pom);

        FixAction missing = FixAction.of(FixAction.ADD_EXCLUSION, "Test", "file", pom.toNioPath().toString(),
                "artifact", "org.example:missing", "exclusion", "org.example:a");
        assertEquals(0, FixRunner.editPoms(getProject(), List.of(missing), "Test"));

        assertEquals(POM, myFixture.getEditor().getDocument().getText());
        assertTrue(reimported.isEmpty());
    }

    public void testGeaendertesDokumentSeitDerVorschauWirdNichtUeberschrieben() throws Exception {
        VirtualFile pom = writePom(POM);
        myFixture.configureFromExistingVirtualFile(pom);
        Document doc = myFixture.getEditor().getDocument();
        List<FixAction> fixes = List.of(exclusion("org.example:a"));
        PomChanges.Plan shown = FixRunner.plan(fixes);
        assertEquals(1, shown.editCount());

        // Nutzer tippt weiter, während der Diff im Editor-Tab steht
        WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.insertString(0, "<!-- neu -->\n"));
        String typed = doc.getText();

        assertEquals(0, FixRunner.editPoms(getProject(), fixes, "Test", shown));
        assertEquals(typed, doc.getText());
        assertTrue(reimported.isEmpty());
    }

    private FixAction exclusion(String exclusion) {
        return FixAction.of(FixAction.ADD_EXCLUSION, "Test", "file", root.resolve("pom.xml").toString(),
                "artifact", "org.example:direct", "exclusion", exclusion);
    }
}
