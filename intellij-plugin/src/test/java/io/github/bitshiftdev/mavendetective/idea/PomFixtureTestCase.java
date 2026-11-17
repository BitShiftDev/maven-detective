package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Grundlage der Plattform-Tests: ein echtes Projektverzeichnis auf der Platte (die POM-Fixes gehen über
 * {@code LocalFileSystem}, der Kern liest gespeicherte Dateien) mit {@code pom.xml} und lokalem Repository
 * unter {@code repo/}. Das Maven-Modell der IDE ersetzt {@link IdeMaven.TestModel}; die Diff-Vorschau ist aus.
 */
public abstract class PomFixtureTestCase extends JavaCodeInsightFixtureTestCase {

    /** Dateien jedes angestoßenen Maven-Imports. */
    protected final List<VirtualFile> reimported = new ArrayList<>();
    protected Path root;
    protected Path repo;
    private boolean previewBefore;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Paths.get(myFixture.getTempDirPath());
        repo = root.resolve("repo");
        IdeMaven.useForTests(new IdeMaven.TestModel(root, repo, root.resolve("pom.xml"), reimported), getTestRootDisposable());
        previewBefore = PomChanges.isPreviewEnabled();
        PomChanges.setPreviewEnabled(false);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            PomChanges.setPreviewEnabled(previewBefore);
        } catch (Throwable e) {
            addSuppressedException(e);
        } finally {
            super.tearDown();
        }
    }

    /**
     * Vor jedem Highlighting aufrufen: {@link PomInsights} startet den Daemon nach jeder geänderten POM und nach
     * jeder im Hintergrund gerechneten Konflikt-Matrix per {@code invokeLater} neu. Die Fixture pumpt die
     * Event-Queue während des Highlightings und verbietet dort einen Neustart. Deshalb gilt die Matrix als
     * bekannt (keine), und anstehende Neustarts laufen vorher.
     */
    protected void settle() {
        PomInsights.getInstance(getProject()).setConflicts(null);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    /** Schreibt die {@code pom.xml} des Projekts auf die Platte (ohne Undo-Eintrag) und liefert sie aus dem VFS. */
    protected VirtualFile writePom(String text) throws IOException {
        return write(root.resolve("pom.xml"), text);
    }

    /** POM {@code g:a:v} im lokalen Repository; {@code dependencies} als {@code g:a:v}. */
    protected void repoPom(String g, String a, String v, String... dependencies) throws IOException {
        StringBuilder deps = new StringBuilder();
        for (String d : dependencies) {
            String[] p = d.split(":");
            deps.append("    <dependency><groupId>").append(p[0]).append("</groupId><artifactId>").append(p[1])
                    .append("</artifactId><version>").append(p[2]).append("</version></dependency>\n");
        }
        Files.createDirectories(artifactDir(g, a, v));
        Files.writeString(artifactDir(g, a, v).resolve(a + "-" + v + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(g, a, v, deps));
    }

    /**
     * JAR {@code g:a:v} im lokalen Repository mit der Klasse {@code type}, als Modul-Bibliothek eingetragen.
     * Die Bytes stammen aus den von Gradle kompilierten Testklassen ({@code testlib}) – die JBR der Test-IDE
     * hat kein {@code javac}.
     */
    protected Path repoJarAsLibrary(String g, String a, String v, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getClassLoader().getResourceAsStream(entry)) {
            assertNotNull(entry, in);
            bytes = in.readAllBytes();
        }
        Path jar = artifactDir(g, a, v).resolve(a + "-" + v + ".jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry(entry));
            zip.write(bytes);
            zip.closeEntry();
        }
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(jar));
        ModuleRootModificationUtil.addModuleLibrary(myFixture.getModule(),
                "jar://" + FileUtil.toSystemIndependentName(jar.toString()) + "!/");
        return jar;
    }

    protected Path artifactDir(String g, String a, String v) {
        return repo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
    }

    protected static VirtualFile write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull(file.toString(), vf);
        vf.refresh(false, false);
        return vf;
    }
}
