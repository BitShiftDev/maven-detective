package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.hints.declarative.HintFormat;
import com.intellij.codeInsight.hints.declarative.InlayActionData;
import com.intellij.codeInsight.hints.declarative.InlayActionHandler;
import com.intellij.codeInsight.hints.declarative.InlayActionPayload;
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector;
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider;
import com.intellij.codeInsight.hints.declarative.InlayTreeSink;
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition;
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector;
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import io.github.bitshiftdev.mavendetective.core.deps.PomHints;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Inlay hinter einer {@code <dependency>} ohne {@code <version>}: „2.18.2 · jackson-bom“ – welche Version
 * dependencyManagement ihr gibt und woher. Klick springt zum verwalteten Eintrag. Abschaltbar unter
 * <i>Settings | Editor | Inlay Hints | Other | XML</i>. Rechnet nur aus dem gespeicherten Stand
 * ({@link PomInsights}, je Datei gecacht) – Tippen kostet nichts.
 */
public final class ManagedVersionInlayProvider implements InlayHintsProvider, DumbAware {

    static final String PROVIDER_ID = "maven.detective.managed.version";
    static final String HANDLER_ID = "maven.detective.open.location";

    @Override
    public @Nullable InlayHintsCollector createCollector(@NotNull PsiFile file, @NotNull Editor editor) {
        if (PomTags.projectTag(file) == null) {
            return null;
        }
        PomInsights.Facts facts = PomInsights.getInstance(file.getProject()).facts((XmlFile) file);
        if (facts.managed().isEmpty()) {
            return null;
        }
        return new Collector(facts);
    }

    private record Collector(PomInsights.Facts facts) implements SharedBypassCollector {

        @Override
        public void collectFromElement(@NotNull PsiElement element, @NotNull InlayTreeSink sink) {
            if (!(element instanceof XmlTag tag) || !"dependency".equals(tag.getLocalName()) || !PomTags.isProjectDependency(tag)
                    || tag.findFirstSubTag("version") != null) {
                return;
            }
            String key = PomTags.managementKey(tag);
            PomHints.ManagedVersion m = key == null ? null : facts.managed().get(key);
            XmlTag artifact = tag.findFirstSubTag("artifactId");
            if (m == null || artifact == null) {
                return;
            }
            InlayActionData jump = new InlayActionData(new StringInlayActionPayload(Locations.of(m.getFile(), m.getLine())), HANDLER_ID);
            String tooltip = MavenDetectiveBundle.message("inlay.managed.tooltip", m.getDescription());
            sink.addPresentation(new InlineInlayPosition(artifact.getTextRange().getEndOffset(), true, 0), null, tooltip,
                    HintFormat.Companion.getDefault(), builder -> {
                        builder.text(m.label(), jump);
                        return Unit.INSTANCE;
                    });
        }
    }

    /** Klick auf ein Inlay mit {@code pfad:zeile}: Datei öffnen. */
    public static final class OpenLocation implements InlayActionHandler {
        @Override
        public void handleClick(@NotNull EditorMouseEvent event, @NotNull InlayActionPayload payload) {
            Project project = event.getEditor().getProject();
            if (project != null && payload instanceof StringInlayActionPayload s) {
                Locations.open(project, s.getText());
            }
        }
    }
}
