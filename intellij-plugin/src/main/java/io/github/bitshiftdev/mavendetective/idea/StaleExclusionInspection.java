package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit;
import io.github.bitshiftdev.mavendetective.core.deps.PomCache;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Modul A4 – Exclusion-Hygiene als Inspection in der {@code pom.xml}: veraltete und doppelte
 * Exclusions werden ausgegraut, Quick-Fix „Exclusion entfernen“. Die Analyse kommt aus dem Kern
 * ({@link ExclusionAudit}) und liest die gespeicherte Datei; die Fundstellen werden über die
 * Struktur (Abschnitt, Koordinaten) aufs PSI abgebildet, nicht über Zeilennummern – so passt es
 * auch, wenn das Dokument gerade noch ungespeicherte Änderungen hat.
 */
public final class StaleExclusionInspection extends LocalInspectionTool {

    private static final Logger LOG = Logger.getInstance(StaleExclusionInspection.class);

    /** Repository-POMs ändern sich praktisch nie; über alle Läufe hinweg gecacht (prüft Änderungszeit). */
    private static final PomCache CACHE = new PomCache();

    @Override
    public ProblemDescriptor @Nullable [] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!(file instanceof XmlFile xml) || !"pom.xml".equals(file.getName())) {
            return null;
        }
        XmlTag project = xml.getRootTag();
        VirtualFile vf = file.getVirtualFile();
        if (project == null || !"project".equals(project.getLocalName()) || vf == null || !vf.isInLocalFileSystem()) {
            return null;
        }
        // Die Analyse liest nur gespeicherte Dateien: Tippen ändert ihr Ergebnis nicht, erst Speichern
        // oder ein VFS-Refresh. Deshalb gecacht bis zur nächsten VFS-Änderung statt pro Highlighting-Lauf.
        List<ExclusionAudit.Finding> findings = CachedValuesManager.getCachedValue(xml,
                () -> CachedValueProvider.Result.create(audit(xml), VirtualFileManager.getInstance()));
        if (findings.isEmpty()) {
            return null;
        }
        List<ProblemDescriptor> problems = new ArrayList<>();
        Set<XmlTag> used = new HashSet<>();
        for (ExclusionAudit.Finding f : findings) {
            if (!f.isRemovable()) {
                continue;
            }
            XmlTag tag = findExclusionTag(project, f, used);
            if (tag == null) {
                continue;
            }
            used.add(tag);
            boolean stale = f.getStatus() == ExclusionAudit.Status.STALE;
            problems.add(manager.createProblemDescriptor(tag, f.message(), isOnTheFly,
                    new LocalQuickFix[] {new RemoveExclusionFix(f.getExclusion().ga())},
                    stale ? ProblemHighlightType.LIKE_UNUSED_SYMBOL : ProblemHighlightType.WEAK_WARNING));
        }
        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    /**
     * Befunde der gespeicherten POM. Läuft in der Read-Action des Daemons; jeder Dateizugriff im Kern
     * prüft {@link ProgressManager#checkCanceled()}, damit ein Tastendruck die Analyse abbricht.
     */
    private static List<ExclusionAudit.Finding> audit(PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        try {
            Project project = file.getProject();
            PomLocator locator = DependenciesPanel.locator(project, IdeMaven.reactorRoot(project), CACHE,
                    ProgressManager::checkCanceled);
            PomFile pom = locator.load(vf.toNioPath());
            return pom == null ? List.of() : ExclusionAudit.audit(locator, pom, null);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Exclusions in " + vf.getPath() + " nicht prüfbar", e);
            return List.of();
        }
    }

    /** Das {@code <exclusion>}-Tag zum Befund; bei doppelten Einträgen das erste noch nicht gemeldete. */
    static @Nullable XmlTag findExclusionTag(XmlTag project, ExclusionAudit.Finding f, Set<XmlTag> used) {
        XmlTag section = project;
        if (f.isManaged()) {
            section = project.findFirstSubTag("dependencyManagement");
        }
        XmlTag deps = section == null ? null : section.findFirstSubTag("dependencies");
        if (deps == null) {
            return null;
        }
        PomFile.Dep d = f.getDependency();
        PomFile.Exclusion e = f.getExclusion();
        for (XmlTag dep : deps.findSubTags("dependency")) {
            if (!d.getGroupId().equals(text(dep, "groupId")) || !d.getArtifactId().equals(text(dep, "artifactId"))) {
                continue;
            }
            XmlTag exclusions = dep.findFirstSubTag("exclusions");
            if (exclusions == null) {
                continue;
            }
            for (XmlTag ex : exclusions.findSubTags("exclusion")) {
                if (!used.contains(ex) && e.getGroupId().equals(text(ex, "groupId")) && e.getArtifactId().equals(text(ex, "artifactId"))) {
                    return ex;
                }
            }
        }
        return null;
    }

    private static @Nullable String text(XmlTag tag, String child) {
        String s = tag.getSubTagText(child);
        return s == null ? null : s.trim();
    }

    /** Entfernt das {@code <exclusion>}; war es das letzte, auch {@code <exclusions>}. Ein Undo-Schritt. */
    static final class RemoveExclusionFix implements LocalQuickFix {
        private final String exclusion;

        RemoveExclusionFix(String exclusion) {
            this.exclusion = exclusion;
        }

        @Override
        public @NotNull String getName() {
            return MavenDetectiveBundle.message("inspection.stale.exclusion.fix", exclusion);
        }

        @Override
        public @NotNull String getFamilyName() {
            return MavenDetectiveBundle.message("inspection.stale.exclusion.family");
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (!(element instanceof XmlTag tag) || !"exclusion".equals(tag.getLocalName())) {
                return;
            }
            XmlTag parent = tag.getParentTag();
            if (parent != null && "exclusions".equals(parent.getLocalName()) && parent.getSubTags().length == 1) {
                parent.delete();
            } else {
                tag.delete();
            }
        }
    }
}
