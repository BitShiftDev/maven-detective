package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** {@link FixRunner#run} je Fix-Typ: was die Karte anstößt, kommt in der POM, im Repository oder in der Zwischenablage an. */
public class FixRunnerRunTest extends PomFixtureTestCase {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example.app</groupId>
              <artifactId>app</artifactId>
              <version>1</version>
              <properties>
                <lib.version>1.0</lib.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>direct</artifactId>
                  <version>1.0</version>
                  <exclusions>
                    <exclusion>
                      <groupId>org.old</groupId>
                      <artifactId>gone</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>unused</artifactId>
                  <version>${lib.version}</version>
                </dependency>
              </dependencies>
              <repositories>
                <repository>
                  <id>corp</id>
                  <url>http://repo.example.com/m2</url>
                </repository>
              </repositories>
            </project>
            """;

    private final List<Notification> notes = Collections.synchronizedList(new ArrayList<>());

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(Notification notification) {
                notes.add(notification);
            }
        });
        CopyPasteManager.getInstance().setContents(new StringSelection(""));
    }

    private BuildDoctorService.Result result(String... goals) {
        BuildFailure failure = new BuildFailure(BuildFailure.Source.LOG);
        failure.getSession().getGoals().addAll(List.of(goals));
        return new BuildDoctorService.Result(failure, List.of(), "[ERROR] x", null, null, null, null, repo);
    }

    private String pomPath() {
        return root.resolve("pom.xml").toString();
    }

    private static String clipboard() {
        return CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
    }

    private boolean noted(NotificationType type, Predicate<String> content) {
        synchronized (notes) {
            return notes.stream().anyMatch(n -> n.getType() == type && content.test(n.getContent()));
        }
    }

    /** Je POM-Fix-Typ: Dokument geändert, gespeichert, Maven-Import angestoßen. */
    public void testJederPomFixAendertDiePomUndStoesstDenImportAn() throws Exception {
        record Case(FixAction fix, Predicate<String> expected) {
        }
        String pom = pomPath();
        List<Case> cases = List.of(
                new Case(FixAction.of(FixAction.PIN_VERSION, "pin", "file", pom, "artifact", "org.lib:lib", "version", "2.0"),
                        t -> t.contains("<dependencyManagement>") && t.contains("<artifactId>lib</artifactId>")),
                new Case(FixAction.of(FixAction.ADD_EXCLUSION, "excl", "file", pom, "artifact", "org.example:direct", "exclusion", "org.x:y"),
                        t -> t.contains("<artifactId>y</artifactId>")),
                new Case(FixAction.of(FixAction.REMOVE_EXCLUSION, "rm", "file", pom, "artifact", "org.example:direct", "exclusion", "org.old:gone"),
                        t -> !t.contains("gone") && !t.contains("<exclusions>")),
                new Case(FixAction.of(FixAction.ADD_DEPENDENCY, "add", "file", pom, "artifact", "org.new:fresh", "version", "3.0"),
                        t -> t.contains("<artifactId>fresh</artifactId>") && t.contains("<version>3.0</version>")),
                new Case(FixAction.of(FixAction.REMOVE_DEPENDENCY, "rm", "file", pom, "artifact", "org.example:unused"),
                        t -> !t.contains("unused")),
                new Case(FixAction.of(FixAction.SET_SCOPE, "scope", "file", pom, "artifact", "org.example:direct", "scope", "test"),
                        t -> t.contains("<scope>test</scope>")),
                new Case(FixAction.of(FixAction.SET_VERSION, "prop", "file", pom, "property", "lib.version", "version", "9.9"),
                        t -> t.contains("<lib.version>9.9</lib.version>")),
                new Case(FixAction.of(FixAction.SET_VERSION, "ver", "file", pom, "artifact", "org.example:direct",
                        "section", "dependencies", "version", "1.1"), t -> t.contains("<version>1.1</version>")),
                new Case(FixAction.of(FixAction.REPLACE_URL, "https", "file", pom, "search", "http://repo.example.com/m2",
                        "replace", "https://repo.example.com/m2"), t -> t.contains("<url>https://repo.example.com/m2</url>")));
        for (Case c : cases) {
            VirtualFile vf = writePom(POM);
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            assertNotNull(doc);
            reimported.clear();

            FixRunner.run(getProject(), c.fix(), result("verify"));

            String type = c.fix().getType() + " " + c.fix().getLabel();
            assertTrue(type + "\n" + doc.getText(), c.expected().test(doc.getText()));
            assertEquals(type, doc.getText(), Files.readString(vf.toNioPath(), StandardCharsets.UTF_8));
            assertEquals(type, List.of(vf), reimported);
        }
    }

    public void testNichtAnwendbarerPomFixMeldetDasUndAendertNichts() throws Exception {
        VirtualFile vf = writePom(POM);
        FixRunner.run(getProject(), FixAction.of(FixAction.SET_SCOPE, "scope", "file", pomPath(), "artifact", "org.none:none",
                "scope", "test"), result("verify"));
        assertEquals(POM, Files.readString(vf.toNioPath(), StandardCharsets.UTF_8));
        assertTrue(reimported.isEmpty());
        assertTrue(notes.toString(), noted(NotificationType.WARNING, t -> t.contains("pom.xml")));
    }

    public void testOeffnetDieDateiInDerGemeldetenZeile() throws Exception {
        VirtualFile vf = writePom(POM);
        FixRunner.run(getProject(), FixAction.of(FixAction.OPEN_FILE, "open", "file", pomPath(), "line", "9"), result());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        Editor editor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull(editor);
        assertEquals(vf, FileDocumentManager.getInstance().getFile(editor.getDocument()));
        assertEquals(8, editor.getCaretModel().getLogicalPosition().line);
    }

    public void testNeubauOhneLaufLegtDenBefehlInDieZwischenablage() {
        FixRunner.run(getProject(), FixAction.of(FixAction.REBUILD, "-U", "args", "-U"), result("clean", "install"));
        assertEquals("mvn clean install -U", clipboard());

        FixRunner.run(getProject(), FixAction.of(FixAction.REBUILD, "-U", "args", "-U"), result());
        assertEquals("ohne Goals im Log: verify", "mvn verify -U", clipboard());
        assertTrue(notes.toString(), noted(NotificationType.INFORMATION, t -> t.contains("verify")));
    }

    /** „Ohne Offline-Modus neu bauen“: der Befehl darf {@code -o} nicht mehr enthalten – vorher passierte gar nichts. */
    public void testOhneOfflineModusNeuBauenLaesstDieOptionWeg() {
        FixRunner.run(getProject(), FixAction.of(FixAction.REBUILD, "offline", "removeArgs", "-o --offline"),
                result("clean", "-o", "install"));
        assertEquals("mvn clean install", clipboard());
        assertTrue(notes.toString(), noted(NotificationType.INFORMATION, t -> t.contains("-o --offline")));
    }

    public void testPluginVersionOhneMavenModellAendertNichts() throws Exception {
        VirtualFile vf = writePom(POM);
        FixRunner.run(getProject(), FixAction.of(FixAction.INSERT_PLUGIN_VERSION, "plugin", "file", pomPath(), "line", "3",
                "plugin", "org.apache.maven.plugins:maven-compiler-plugin"), result());
        assertEquals(POM, Files.readString(vf.toNioPath(), StandardCharsets.UTF_8));
        assertTrue(notes.toString(), noted(NotificationType.WARNING, t -> t.contains("maven-compiler-plugin")));

        notes.clear();
        FixRunner.run(getProject(), FixAction.of(FixAction.INSERT_PLUGIN_VERSION, "ohne Koordinaten", "file", pomPath()), result());
        assertTrue(notes.toString(), noted(NotificationType.INFORMATION, t -> t.contains("ohne Koordinaten")));
    }

    public void testLoeschenNurImLokalenRepository() throws Exception {
        Path inRepo = artifactDir("org.x", "a", "1").resolve("a-1.jar.lastUpdated");
        Path outside = root.resolve("keep.txt");
        Files.createDirectories(inRepo.getParent());
        Files.writeString(inRepo, "x");
        Files.writeString(outside, "x");

        FixRunner.run(getProject(), FixAction.of(FixAction.DELETE_FILES, "delete", "files", inRepo.toString()), result());
        PlatformTestUtil.waitWithEventsDispatching("Datei im Repository gelöscht", () -> !Files.exists(inRepo), 30);

        FixRunner.run(getProject(), FixAction.of(FixAction.DELETE_FILES, "delete", "files", outside.toString()), result());
        PlatformTestUtil.waitWithEventsDispatching("Ablehnung gemeldet", () -> noted(NotificationType.ERROR, t -> t.contains("keep.txt")), 30);
        assertTrue(Files.exists(outside));
    }

    public void testJvmOptionLandetInJvmConfig() throws Exception {
        Path config = root.resolve(".mvn/jvm.config");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "-Dfile.encoding=UTF-8\n");
        FixRunner.run(getProject(), FixAction.of(FixAction.SET_JVM_OPTION, "jvm", "file", config.toString(), "option", "-Xmx2g"),
                result());
        PlatformTestUtil.waitWithEventsDispatching("jvm.config geschrieben", () -> {
            try {
                return Files.readString(config).contains("-Xmx2g");
            } catch (java.io.IOException e) {
                return false;
            }
        }, 30);
        assertTrue(Files.readString(config).contains("-Dfile.encoding=UTF-8"));
    }

    public void testNurBeschriebeneFixesMeldenIhrenText() {
        FixRunner.run(getProject(), FixAction.of(FixAction.MANUAL, "Von Hand: settings.xml prüfen"), result());
        assertTrue(notes.toString(), noted(NotificationType.INFORMATION, t -> t.contains("settings.xml prüfen")));
        assertTrue(reimported.isEmpty());
    }
}
