package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * PSI-Helfer für die Editor-Hinweise in der {@code pom.xml} (U4). Befunde aus dem Kern werden über die
 * Struktur aufs PSI abgebildet (Abschnitt, Koordinaten), nie über Zeilennummern – die gespeicherte
 * Datei und das Dokument dürfen auseinanderliegen.
 */
final class PomTags {

    private PomTags() {
    }

    /** Wurzel-Tag {@code <project>} einer POM des Projekts (auf der Platte), sonst {@code null}. */
    static @Nullable XmlTag projectTag(@Nullable PsiFile file) {
        if (!(file instanceof XmlFile xml) || !"pom.xml".equals(file.getName())) {
            return null;
        }
        VirtualFile vf = file.getVirtualFile();
        XmlTag root = xml.getRootTag();
        return vf == null || !vf.isInLocalFileSystem() || root == null || !"project".equals(root.getLocalName()) ? null : root;
    }

    /** {@code <dependency>}-Tags unter {@code project/dependencies}; mit {@code managed} auch unter {@code dependencyManagement}. */
    static List<XmlTag> dependencies(@NotNull XmlTag project, boolean managed) {
        List<XmlTag> out = new ArrayList<>();
        add(out, project.findFirstSubTag("dependencies"));
        if (managed) {
            XmlTag dm = project.findFirstSubTag("dependencyManagement");
            add(out, dm == null ? null : dm.findFirstSubTag("dependencies"));
        }
        return out;
    }

    private static void add(List<XmlTag> out, @Nullable XmlTag deps) {
        if (deps != null) {
            for (XmlTag d : deps.findSubTags("dependency")) {
                out.add(d);
            }
        }
    }

    /** Steht das {@code <dependency>} direkt unter {@code project/dependencies}? */
    static boolean isProjectDependency(@NotNull XmlTag dependency) {
        XmlTag deps = dependency.getParentTag();
        XmlTag project = deps == null ? null : deps.getParentTag();
        return "dependency".equals(dependency.getLocalName()) && deps != null && "dependencies".equals(deps.getLocalName())
                && project != null && "project".equals(project.getLocalName()) && project.getParentTag() == null;
    }

    /** Steht das {@code <dependency>} unter {@code project/dependencies} oder {@code project/dependencyManagement/dependencies}? */
    static boolean isTopLevelDependency(@NotNull XmlTag dependency) {
        if (isProjectDependency(dependency)) {
            return true;
        }
        XmlTag deps = dependency.getParentTag();
        XmlTag dm = deps == null ? null : deps.getParentTag();
        XmlTag project = dm == null ? null : dm.getParentTag();
        return "dependency".equals(dependency.getLocalName()) && deps != null && "dependencies".equals(deps.getLocalName())
                && dm != null && "dependencyManagement".equals(dm.getLocalName()) && project != null && "project".equals(project.getLocalName())
                && project.getParentTag() == null;
    }

    /**
     * Das {@code <dependency>}-Tag, dessen öffnender Name {@code element} ist – Gutter-Icons hängen an
     * Blättern, sonst ruft die Plattform den Provider für jedes Element einzeln.
     */
    static @Nullable XmlTag dependencyOfNameToken(@NotNull PsiElement element) {
        if (!(element instanceof XmlToken token) || token.getTokenType() != XmlTokenType.XML_NAME
                || !(element.getParent() instanceof XmlTag tag) || !"dependency".equals(tag.getLocalName())) {
            return null;
        }
        // nur der Name im öffnenden Tag, nicht der in </dependency>
        for (PsiElement c = tag.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof XmlToken t && t.getTokenType() == XmlTokenType.XML_NAME) {
                return c == element ? tag : null;
            }
        }
        return null;
    }

    static @Nullable String text(@NotNull XmlTag tag, @NotNull String child) {
        String s = tag.getSubTagText(child);
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * {@code groupId:artifactId} des Tags; {@code ${project.groupId}} u. Ä. wird durch {@code moduleGroupId}
     * ersetzt (wie bei Geschwister-Modulen üblich). {@code null}, wenn unvollständig.
     */
    static @Nullable String ga(@NotNull XmlTag dependency, @Nullable String moduleGroupId) {
        String g = text(dependency, "groupId");
        String a = text(dependency, "artifactId");
        if (g == null || a == null) {
            return null;
        }
        if (g.contains("${")) {
            if (moduleGroupId == null) {
                return null;
            }
            g = moduleGroupId;
        }
        return g + ":" + a;
    }

    /** Schlüssel wie {@code PomFile.Dep.managementKey()} – roh, wie in der Datei notiert. */
    static @Nullable String managementKey(@NotNull XmlTag dependency) {
        String g = text(dependency, "groupId");
        String a = text(dependency, "artifactId");
        if (g == null || a == null) {
            return null;
        }
        String type = text(dependency, "type");
        String classifier = text(dependency, "classifier");
        return g + ":" + a + ":" + (type == null ? "jar" : type) + (classifier == null ? "" : ":" + classifier);
    }
}
