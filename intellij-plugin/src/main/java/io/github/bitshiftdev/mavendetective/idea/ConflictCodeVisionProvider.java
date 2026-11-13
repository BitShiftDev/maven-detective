package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind;
import com.intellij.codeInsight.codeVision.CodeVisionEntry;
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering;
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider;
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry;
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix;
import kotlin.Pair;
import kotlin.Unit;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Code Vision über einer {@code <dependency>}, deren Version über die Module abweicht: „3 Module ·
 * 2 Versionen“; Klick öffnet die Seite „Konflikte“ mit dieser Zeile. Grundlage ist die Konflikt-Matrix
 * des letzten Maven-Imports ({@link PomInsights}); sie rechnet im Hintergrund, der Highlighting-Lauf
 * liest nur. Abschaltbar unter <i>Settings | Editor | Inlay Hints | Code vision</i>.
 */
public final class ConflictCodeVisionProvider implements DaemonBoundCodeVisionProvider {

    static final String ID = "maven.detective.conflicts";

    @Override
    public @NotNull List<Pair<TextRange, CodeVisionEntry>> computeForEditor(@NotNull Editor editor, @NotNull PsiFile file) {
        XmlTag project = PomTags.projectTag(file);
        if (project == null) {
            return List.of();
        }
        PomInsights.Facts facts = PomInsights.getInstance(file.getProject()).facts((XmlFile) file);
        if (facts.conflicts().isEmpty()) {
            return List.of();
        }
        List<Pair<TextRange, CodeVisionEntry>> out = new ArrayList<>();
        for (XmlTag dep : PomTags.dependencies(project, true)) {
            String ga = PomTags.ga(dep, facts.groupId());
            ConflictMatrix.Row row = ga == null ? null : facts.conflicts().get(ga);
            if (row == null) {
                continue;
            }
            String text = label(row.getCells().size(), row.versions().size());
            String tooltip = MavenDetectiveBundle.message("codevision.conflicts.tooltip", ga, String.join(", ", row.versions()));
            out.add(new Pair<>(dep.getTextRange(), new ClickableTextCodeVisionEntry(text, ID, (event, ed) -> {
                open(ed.getProject(), ga);
                return Unit.INSTANCE;
            }, AllIcons.General.Warning, text, tooltip, List.of())));
        }
        return out;
    }

    static @Nls String label(int modules, int versions) {
        return MavenDetectiveBundle.message("codevision.conflicts", modules, versions);
    }

    private static void open(Project project, String ga) {
        if (project != null) {
            Navigator.open(project, ConflictMatrixPanel.class, p -> p.select(ga));
        }
    }

    @Override
    public @NotNull String getName() {
        return MavenDetectiveBundle.message("codevision.conflicts.name");
    }

    @Override
    public @NotNull List<CodeVisionRelativeOrdering> getRelativeOrderings() {
        return List.of(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingLast.INSTANCE);
    }

    @Override
    public @NotNull CodeVisionAnchorKind getDefaultAnchor() {
        return CodeVisionAnchorKind.Top;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    /** Eintrag in den Code-Vision-Einstellungen (ein- und ausschaltbar). */
    public static final class Settings implements CodeVisionGroupSettingProvider {
        @Override
        public @NotNull String getGroupId() {
            return ID;
        }

        @Override
        public @NotNull String getGroupName() {
            return MavenDetectiveBundle.message("codevision.conflicts.name");
        }

        @Override
        public @NotNull String getDescription() {
            return MavenDetectiveBundle.message("codevision.conflicts.description");
        }
    }
}
