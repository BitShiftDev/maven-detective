package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.QuickFix;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiUtilCore;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath;
import io.github.bitshiftdev.mavendetective.core.deps.PomCache;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modul A5 live im Editor: ein Import, dessen Klasse aus einem nur transitiv vorhandenen JAR kommt,
 * wird markiert; Quick-Fix deklariert die Dependency in der {@code pom.xml} des Moduls. Der Classpath
 * kommt aus dem Kern ({@link ModuleClasspath} mit dem Baum der IDE) und bleibt gecacht, bis sich die
 * Projektstruktur (Maven-Import) oder die POM ändert.
 */
public final class UndeclaredDependencyInspection extends LocalInspectionTool {

    private static final Logger LOG = Logger.getInstance(UndeclaredDependencyInspection.class);
    private static final Key<com.intellij.psi.util.CachedValue<ModuleClasspath>> CLASSPATH = Key.create("mavenDetective.classpath");
    private static final PomCache CACHE = IdeCaches.POMS;

    /** Auch melden, wenn die Klasse über einen Starter ohne eigene Klassen kommt (Spring Boot & Co.). */
    public boolean reportViaStarters = false;

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(OptPane.checkbox("reportViaStarters",
                MavenDetectiveBundle.message("inspection.undeclared.option.starters")));
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        PsiFile file = holder.getFile();
        VirtualFile vf = file.getVirtualFile();
        Module module = vf == null ? null : ModuleUtilCore.findModuleForFile(vf, file.getProject());
        if (module == null || !ProjectFileIndex.getInstance(file.getProject()).isInSourceContent(vf) || IdeMaven.pom(module) == null) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        boolean test = ProjectFileIndex.getInstance(file.getProject()).isInTestSourceContent(vf);
        return new JavaElementVisitor() {
            @Override
            public void visitImportStatement(@NotNull com.intellij.psi.PsiImportStatement statement) {
                check(statement);
            }

            @Override
            public void visitImportStaticStatement(@NotNull com.intellij.psi.PsiImportStaticStatement statement) {
                check(statement);
            }

            private void check(PsiImportStatementBase statement) {
                if (statement.isOnDemand() && !(statement instanceof com.intellij.psi.PsiImportStaticStatement)) {
                    return; // import a.b.* – welche Klassen gemeint sind, sieht der Tab
                }
                PsiJavaCodeReferenceElement ref = statement.getImportReference();
                PsiClass cls = ref == null ? null : IdeUsages.referencedClass(ref);
                if (cls == null || cls.getQualifiedName() == null) {
                    return;
                }
                ModuleClasspath cp = classpath(module);
                ModuleClasspath.Entry entry = cp == null ? null : entryOf(cls, module, cp);
                if (entry == null) {
                    return;
                }
                int line = StringUtil.offsetToLineNumber(file.getViewProvider().getContents(), statement.getTextRange().getStartOffset()) + 1;
                DependencyUsage.Finding f = DependencyUsage.undeclared(cp, entry, cls.getQualifiedName(), test, vf.toNioPath(), line,
                        IdeUsages.JARS);
                if (f == null || f.isViaBundle() && !reportViaStarters) {
                    return;
                }
                List<FixAction> fixes = f.fixes();
                LocalQuickFix[] quickFixes = fixes.isEmpty() ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[] {new DeclareFix(fixes.get(0))};
                holder.registerProblem(ref, f.message(), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, quickFixes);
            }
        };
    }

    /** Classpath-Eintrag, aus dem die Klasse kommt: JAR über den Pfad, Reactor-Modul über seine Koordinaten. */
    private static @Nullable ModuleClasspath.Entry entryOf(PsiClass cls, Module current, ModuleClasspath cp) {
        VirtualFile file = PsiUtilCore.getVirtualFile(cls);
        if (file == null) {
            return null;
        }
        VirtualFile jar = VfsUtilCore.getVirtualFileForJar(file);
        if (jar != null) {
            return jar.isInLocalFileSystem() ? cp.find(jar.toNioPath()) : null;
        }
        Module other = ModuleUtilCore.findModuleForFile(file, cls.getProject());
        if (other == null || other.equals(current)) {
            return null;
        }
        String ga = IdeMaven.ga(other);
        int colon = ga == null ? -1 : ga.indexOf(':');
        return colon < 0 ? null : cp.find(ga.substring(0, colon), ga.substring(colon + 1));
    }

    /** Classpath des Moduls; gecacht bis zum nächsten Maven-Import (Projektstruktur) oder einer Änderung der POM. */
    private static @Nullable ModuleClasspath classpath(Module module) {
        return CachedValuesManager.getManager(module.getProject()).getCachedValue(module, CLASSPATH, () -> {
            Path pom = IdeMaven.pom(module);
            VirtualFile pomFile = pom == null ? null : LocalFileSystem.getInstance().findFileByNioFile(pom);
            ModuleClasspath cp = pom == null ? null : compute(module.getProject(), pom);
            return pomFile == null
                    ? CachedValueProvider.Result.create(cp, ProjectRootManager.getInstance(module.getProject()))
                    : CachedValueProvider.Result.create(cp, ProjectRootManager.getInstance(module.getProject()), pomFile);
        }, false);
    }

    private static @Nullable ModuleClasspath compute(Project project, Path pom) {
        try {
            PomLocator locator = DependenciesPanel.locator(project, IdeMaven.reactorRoot(project), CACHE, ProgressManager::checkCanceled);
            PomFile pomFile = locator.load(pom);
            if (pomFile == null) {
                return null;
            }
            Map<String, PomFile> reactor = new java.util.HashMap<>();
            for (IdeMaven.ModuleRef ref : IdeMaven.modules(project)) {
                PomFile p = locator.load(ref.pom());
                if (p != null) {
                    reactor.put(p.getGroupId() + ":" + p.getArtifactId(), p);
                }
            }
            return ModuleClasspath.of(locator, pomFile, IdeMaven.dependencyTree(project, pom), reactor);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Classpath von " + pom + " nicht ermittelbar", e);
            return null;
        }
    }

    /** Deklariert die Dependency in der POM des Moduls – ein Undo-Schritt, danach Maven-Import. */
    /**
     * Auch als {@link BatchQuickFix}: „Alle beheben“ über mehrere Imports aus derselben Dependency deklariert sie einmal
     * (ein Undo-Schritt, eine Vorschau) statt N-mal mit N−1 Warnungen „schon deklariert“.
     */
    static final class DeclareFix implements LocalQuickFix, BatchQuickFix {
        private final FixAction fix;

        DeclareFix(FixAction fix) {
            this.fix = fix;
        }

        @Override
        public @NotNull String getName() {
            return fix.getLabel();
        }

        @Override
        public @NotNull String getFamilyName() {
            return MavenDetectiveBundle.message("inspection.undeclared.family");
        }

        @Override
        public boolean startInWriteAction() {
            return false; // ändert eine andere Datei (pom.xml) über FixRunner
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (alreadyDeclared(fix)) {
                return;   // ein Fix davor (gleiche Dependency, anderer Import) hat sie schon eingetragen
            }
            FixRunner.editPoms(project, List.of(fix), MavenDetectiveBundle.message("command.declare.dependency"));
        }

        @Override
        public void applyFix(@NotNull Project project, CommonProblemDescriptor @NotNull [] descriptors,
                             @NotNull List<PsiElement> psiElementsToIgnore, @Nullable Runnable refreshViews) {
            Map<String, FixAction> distinct = new LinkedHashMap<>();
            for (CommonProblemDescriptor d : descriptors) {
                QuickFix<?>[] fixes = d.getFixes();
                if (fixes == null) {
                    continue;
                }
                for (QuickFix<?> q : fixes) {
                    if (q instanceof DeclareFix df && !alreadyDeclared(df.fix)) {
                        distinct.putIfAbsent(df.fix.param("file") + "|" + df.fix.param("artifact") + "|" + df.fix.param("classifier"), df.fix);
                    }
                }
            }
            if (!distinct.isEmpty()) {
                FixRunner.editPoms(project, new ArrayList<>(distinct.values()), MavenDetectiveBundle.message("command.declare.dependency"));
            }
            if (refreshViews != null) {
                refreshViews.run();
            }
        }

        /** Steht die Dependency schon unter {@code <dependencies>} der POM (auch ungespeichert im Editor)? */
        static boolean alreadyDeclared(FixAction fix) {
            String file = fix.param("file");
            String artifact = fix.param("artifact");
            if (file == null || artifact == null) {
                return false;
            }
            VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(Paths.get(file));
            com.intellij.openapi.editor.Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) {
                return false;
            }
            try {
                PomFile pom = PomFile.parse(Paths.get(file), doc.getText());
                String classifier = fix.param("classifier");
                for (PomFile.Dep d : pom.getDependencies()) {
                    if (artifact.equals(d.ga()) && java.util.Objects.equals(emptyToNull(classifier), emptyToNull(d.getClassifier()))) {
                        return true;
                    }
                }
            } catch (Exception e) {
                return false;   // halb getippte POM: dann entscheidet der Fix selbst
            }
            return false;
        }

        private static @Nullable String emptyToNull(@Nullable String s) {
            return s == null || s.isEmpty() ? null : s;
        }
    }
}
