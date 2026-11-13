package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.core.catalog.Rule;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * „Falsch erkannt?“: richtige Kategorie wählen (oder „weiß nicht“), optional die echte Ursache
 * beschreiben. Der Log wird als Testfall unter {@code ~/.maven-detective/testfaelle} abgelegt –
 * nichts verlässt den Rechner.
 */
final class MisdiagnosisDialog extends DialogWrapper {

    /** Eintrag der Auswahl; {@code id == null} = unbekannt. */
    record Choice(@Nullable String id, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final Project project;
    private final BuildDoctorService.Result result;
    private final ComboBox<Choice> rule;
    private final JBTextArea note = new JBTextArea(4, 40);

    MisdiagnosisDialog(@NotNull Project project, @NotNull BuildDoctorService.Result result) {
        super(project);
        this.project = project;
        this.result = result;
        String detected = result.diagnoses().isEmpty() ? null : result.diagnoses().get(0).getRuleId();
        rule = new ComboBox<>(choices(RuleCatalog.loadDefault().getRules(), detected).toArray(new Choice[0]));
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.getEmptyText().setText(MavenDetectiveBundle.message("misdiagnosis.note.placeholder"));
        setTitle(MavenDetectiveBundle.message("misdiagnosis.title"));
        setOKButtonText(MavenDetectiveBundle.message("misdiagnosis.save"));
        init();
    }

    /** „Weiß nicht“ zuerst, dann alle Regeln außer der erkannten, nach Nummer. */
    static @NotNull List<Choice> choices(@NotNull List<Rule> rules, @Nullable String detected) {
        List<Choice> out = new ArrayList<>();
        out.add(new Choice(null, MavenDetectiveBundle.message("misdiagnosis.unknown")));
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort((a, b) -> Integer.compare(a.getNumber() <= 0 ? Integer.MAX_VALUE : a.getNumber(),
                b.getNumber() <= 0 ? Integer.MAX_VALUE : b.getNumber()));
        for (Rule r : sorted) {
            if (!r.getId().equals(detected)) {
                out.add(new Choice(r.getId(), (r.getNumber() > 0 ? "#" + r.getNumber() + " " : "") + r.getCategory() + " (" + r.getId() + ")"));
            }
        }
        return out;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        String detected = result.diagnoses().isEmpty() ? MavenDetectiveBundle.message("misdiagnosis.detected.none")
                : DiagnosisCard.headline(result.diagnoses().get(0));
        JBScrollPane noteScroll = new JBScrollPane(note);
        JComponent form = FormBuilder.createFormBuilder()
                .addLabeledComponent(MavenDetectiveBundle.message("misdiagnosis.detected"), new javax.swing.JLabel(detected))
                .addLabeledComponent(MavenDetectiveBundle.message("misdiagnosis.correct"), rule)
                .addLabeledComponent(MavenDetectiveBundle.message("misdiagnosis.note"), noteScroll, true)
                .addComponent(DiagnosisCard.text(MavenDetectiveBundle.message("misdiagnosis.hint"), com.intellij.util.ui.JBFont.small()))
                .getPanel();
        form.setPreferredSize(JBUI.size(460, 240));
        return form;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return rule;
    }

    @Override
    protected void doOKAction() {
        Choice c = (Choice) rule.getSelectedItem();
        FixRunner.reportMisdiagnosis(project, result, c == null ? null : c.id(), note.getText());
        super.doOKAction();
    }
}
