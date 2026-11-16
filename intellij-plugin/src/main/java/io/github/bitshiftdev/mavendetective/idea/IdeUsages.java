package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStaticReferenceElement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiPackageStatement;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.ClassUsages;
import io.github.bitshiftdev.mavendetective.core.deps.JarIndex;
import io.github.bitshiftdev.mavendetective.core.deps.SourceScanner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Modul A5 in der IDE: welche Klassen die Quellen eines Maven-Moduls nennen – über den Code-Index
 * aufgelöst statt geraten. Java-Dateien laufen über die PSI (jede Typ-Referenz, jeder Import, jede
 * statische Nennung wird aufgelöst), Kotlin und Groovy über den Text-Scanner des Kerns.
 */
final class IdeUsages {

    /** JAR-Inhalte über alle Läufe (mtime + Größe) – für Tab, Inspection und „Doppelte Klassen“; siehe {@link IdeCaches}. */
    static final JarIndex JARS = IdeCaches.JARS;

    private IdeUsages() {
    }

    /**
     * Genannte Klassen des Moduls zu {@code pom}. Hintergrund-Thread, nie in einer Read-Action
     * aufrufen: jede Datei bekommt ihre eigene, nicht-blockierende Read-Action im Smart Mode, damit
     * Schreibzugriffe nicht auf die ganze Analyse warten. Ist das Modul nicht importiert, liest der Text-Scanner.
     */
    static ClassUsages collect(@NotNull Project project, @NotNull Path pom, @NotNull Cancellation cancel) {
        Module module = IdeMaven.module(project, pom);
        if (module == null) {
            return SourceScanner.scan(pom.getParent(), cancel);
        }
        List<VirtualFile> files = IdeMaven.read(() -> sourceFiles(module));
        ClassUsages usages = new ClassUsages();
        for (VirtualFile f : files) {
            cancel.check();
            // nicht-blockierend: ein Schreibzugriff bricht ab und startet die Datei neu – daher je Datei frisch sammeln
            ClassUsages one = ReadAction.nonBlocking(() -> {
                        ClassUsages u = new ClassUsages();
                        if (f.isValid() && !project.isDisposed()) {
                            scan(project, f, u);
                        }
                        return u;
                    })
                    .inSmartMode(project)
                    // „Stop“ greift auch, während auf das Ende der Indizierung gewartet wird
                    .expireWhen(() -> isCancelled(cancel))
                    .executeSynchronously();
            usages.addAll(one);
        }
        return usages;
    }

    private static boolean isCancelled(Cancellation cancel) {
        try {
            cancel.check();
            return false;
        } catch (ProcessCanceledException e) {
            return true;
        }
    }

    /** Quelldateien (auch Test und erzeugte) des Moduls. Read-Action. */
    private static List<VirtualFile> sourceFiles(Module module) {
        List<VirtualFile> out = new ArrayList<>();
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(true)) {
            VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file) {
                    if (!file.isDirectory() && SourceScanner.isSource(file.getName())) {
                        out.add(file);
                    }
                    return true;
                }
            });
        }
        return out;
    }

    private static void scan(Project project, VirtualFile f, ClassUsages usages) {
        boolean test = ProjectFileIndex.getInstance(project).isInTestSourceContent(f);
        Path path = f.toNioPath();
        PsiFile psi = PsiManager.getInstance(project).findFile(f);
        if (psi instanceof PsiJavaFile java) {
            usages.countFile(test);
            CharSequence text = java.getViewProvider().getContents();
            java.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override
                public void visitReferenceElement(@NotNull PsiJavaCodeReferenceElement reference) {
                    record(reference);
                    super.visitReferenceElement(reference);
                }

                @Override
                public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                    record(expression);
                    super.visitReferenceExpression(expression);
                }

                private void record(PsiJavaCodeReferenceElement ref) {
                    String name = ref.getReferenceName();
                    // a.b() oder feld: keine Klasse – nur Großgeschriebenes auflösen, das spart die meisten resolve()-Aufrufe
                    if (name == null || ref instanceof PsiReferenceExpression && (name.isEmpty() || !Character.isUpperCase(name.charAt(0)))) {
                        return;
                    }
                    if (PsiTreeUtil.getParentOfType(ref, PsiPackageStatement.class) != null) {
                        return;
                    }
                    PsiClass cls = referencedClass(ref);
                    String q = cls == null ? null : cls.getQualifiedName();
                    if (q == null || q.startsWith("java.")) {
                        return;
                    }
                    usages.add(q, test, path, StringUtil.offsetToLineNumber(text, ref.getTextRange().getStartOffset()) + 1);
                }
            });
        } else {
            CharSequence text = psi != null ? psi.getViewProvider().getContents() : LoadTextUtil.loadText(f);
            SourceScanner.scanText(text.toString(), test, path, usages);
        }
    }

    /** Äußere Klasse, auf die {@code ref} zeigt (bei Membern deren Klasse); {@code null} bei Paketen, Typvariablen, Unaufgelöstem. */
    static @Nullable PsiClass referencedClass(@NotNull PsiJavaCodeReferenceElement ref) {
        PsiElement target = ref instanceof PsiImportStaticReferenceElement s ? s.getClassReference().resolve() : ref.resolve();
        PsiClass cls = target instanceof PsiClass c ? c : target instanceof PsiMember m ? m.getContainingClass() : null;
        if (cls == null || cls instanceof PsiTypeParameter) {
            return null;
        }
        return PsiUtil.getTopLevelClass(cls);
    }
}
